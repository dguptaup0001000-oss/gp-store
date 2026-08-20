import 'dart:async';
import 'dart:ui';

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import '../api/api_client.dart';
import 'voice_announcement_service.dart';

/// Runs when a push arrives while the app is fully backgrounded/terminated.
/// Must be a top-level (or static) function - Firebase spins this up in its
/// own isolate, so it can't be a class method or close over any app state.
///
/// The visible notification does NOT depend on this handler: the OS shows it
/// automatically because the backend sends a `notification` payload (see
/// PushNotificationService.java), not a silent data-only one. That is what
/// makes the shop see a new order with the app closed, and it keeps working
/// whether or not anything below succeeds.
///
/// What this adds is the SPOKEN half for a new order, attempted from the
/// background isolate.
///
/// BEST EFFORT, AND GENUINELY SO - worth being precise rather than implying
/// a guarantee:
///
///   - Android gives a background isolate a short, unspecified window and
///     may kill it before the engine finishes speaking.
///   - Aggressive battery optimisation on many Indian OEM builds (Xiaomi,
///     Oppo, Vivo, Samsung) restricts background execution further, and the
///     shop may need to exempt the app for this to be dependable.
///   - The device's own media/silent state still applies.
///
/// So the counter phone should be treated as reliably announcing while the
/// app is OPEN, and often - not always - while it is backgrounded. The
/// notification itself is unaffected either way.
@pragma('vm:entry-point')
Future<void> firebaseBackgroundMessageHandler(RemoteMessage message) async {
  debugPrint('Background push received: ${message.messageId}');

  if (message.data['type'] != 'NEW_ORDER') return;

  final orderId = message.data['orderId'];
  final customerName = message.data['customerName'];
  final orderAmount = message.data['orderAmount'];

  // orderId is the deduplication key. It matters most on exactly this path:
  // a push may be handled here in the background isolate and then, if the
  // shop opens the app moments later, be handled again by the foreground
  // listener. Both isolates claim against the same persisted log, so the
  // order is spoken once - but only because both pass the same real id.
  if (orderId == null || customerName == null || orderAmount == null) return;

  try {
    // Required before touching any plugin from a background isolate: this
    // isolate did not run main(), so no plugins are registered in it and the
    // TTS channel would otherwise not exist.
    DartPluginRegistrant.ensureInitialized();

    // A fresh service instance - the app's Riverpod-managed one lives in the
    // main isolate and is unreachable from here.
    await VoiceAnnouncementService().announceNewOrder(
      orderId: orderId,
      customerName: customerName,
      rupees: orderAmount,
    );
  } catch (e) {
    // The notification is already on screen and the order is already placed.
    // Nothing here is allowed to matter beyond a log line.
    debugPrint('Background voice announcement failed (notification unaffected): $e');
  }
}

/// Wraps Firebase Cloud Messaging - requests permission, gets this device's
/// token, keeps the backend's copy of it in sync (including when Firebase
/// rotates it), and surfaces incoming messages while the app is open or
/// just got opened from a tap.
///
/// Deliberately UI-free: `onForegroundMessage`/`onNotificationTap` are
/// plain callbacks the caller supplies (see main.dart), so this class has
/// no dependency on BuildContext/Navigator and stays easy to reason about
/// independent of any particular screen.
class PushNotificationService {
  PushNotificationService({required this.apiClient});

  final ApiClient apiClient;

  StreamSubscription<String>? _tokenRefreshSub;
  StreamSubscription<RemoteMessage>? _foregroundSub;
  StreamSubscription<RemoteMessage>? _openedAppSub;

  final FlutterLocalNotificationsPlugin _localNotifications =
      FlutterLocalNotificationsPlugin();
  bool _localNotificationsReady = false;

  /// Call once, right after login/session-restore succeeds (see
  /// main.dart's auth-state listener) - registers this device for push and
  /// starts listening for messages. Safe to call again on every login (a
  /// fresh permission prompt won't reappear if already granted/denied).
  Future<void> start({
    void Function(RemoteMessage message)? onForegroundMessage,
    void Function(RemoteMessage message)? onNotificationTap,
  }) async {
    try {
      final messaging = FirebaseMessaging.instance;

      // iOS/web require this explicit prompt; Android 13+ (API 33) also
      // needs it - FirebaseMessaging.requestPermission() triggers the
      // POST_NOTIFICATIONS runtime prompt itself on recent plugin
      // versions, no extra native code needed. Android 12 and below just
      // returns "authorized" immediately since no such prompt exists there.
      await messaging.requestPermission(alert: true, badge: true, sound: true);

      final token = await messaging.getToken();
      if (token != null) {
        await _registerToken(token);
      }

      // Tokens rotate periodically (reinstall, Firebase-initiated
      // rotation, etc.) - without this listener, a customer would silently
      // stop receiving push after a rotation until something else happened
      // to call getToken() again.
      await _tokenRefreshSub?.cancel();
      _tokenRefreshSub = messaging.onTokenRefresh.listen(_registerToken);

      await _foregroundSub?.cancel();
      _foregroundSub = FirebaseMessaging.onMessage.listen((message) {
        _showLocalNotification(message);
        onForegroundMessage?.call(message);
      });

      await _openedAppSub?.cancel();
      if (onNotificationTap != null) {
        // App was backgrounded (not terminated) and the user tapped the
        // system notification to bring it back to the foreground.
        _openedAppSub = FirebaseMessaging.onMessageOpenedApp.listen(onNotificationTap);

        // App was fully terminated and got launched BY tapping the
        // notification - onMessageOpenedApp above never fires for this
        // case, only getInitialMessage() catches it.
        final initialMessage = await messaging.getInitialMessage();
        if (initialMessage != null) {
          onNotificationTap(initialMessage);
        }
      }
    } catch (e) {
      // Never let a push-setup failure (permission denied, Firebase not
      // configured on this build yet, etc.) block the app from being
      // usable - push is an enhancement, not a requirement to shop/deliver.
      debugPrint('Push notification setup failed (app continues normally): $e');
    }
  }

  /// One-time setup: creates the Android notification channel (with sound)
  /// and initializes the plugin. Must run before _showLocalNotification, or
  /// the channel won't exist yet and the banner/sound won't fire.
  Future<void> _initLocalNotifications() async {
    if (_localNotificationsReady) return;

    const androidSettings =
        AndroidInitializationSettings('@mipmap/ic_launcher');
    const initSettings = InitializationSettings(android: androidSettings);
    await _localNotifications.initialize(initSettings);

    const channel = AndroidNotificationChannel(
      'gp_store_orders',
      'Order updates',
      description: 'Order status changes and delivery assignments',
      importance: Importance.high,
      playSound: true,
    );
    await _localNotifications
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(channel);

    _localNotificationsReady = true;
  }

  /// FCM stays silent in the foreground on Android by design - this is what
  /// makes it actually show a banner + play sound while the app is open.
  Future<void> _showLocalNotification(RemoteMessage message) async {
    final notification = message.notification;
    if (notification == null) return;

    try {
      await _initLocalNotifications();

      const androidDetails = AndroidNotificationDetails(
        'gp_store_orders',
        'Order updates',
        channelDescription: 'Order status changes and delivery assignments',
        importance: Importance.high,
        priority: Priority.high,
        playSound: true,
      );
      const details = NotificationDetails(android: androidDetails);

      await _localNotifications.show(
        message.hashCode,
        notification.title,
        notification.body,
        details,
      );
    } catch (e) {
      debugPrint('Failed to show local notification: $e');
    }
  }

  Future<void> _registerToken(String token) async {
    try {
      await apiClient.dio.put('/api/customers/me/fcm-token', data: {'fcmToken': token});
    } catch (e) {
      debugPrint('Failed to register FCM token with backend: $e');
    }
  }

  /// Call on logout - stops listening, though the backend's stored token
  /// for this account is deliberately left as-is (harmless: the next login
  /// on this device re-registers it anyway, and a stale token just means a
  /// silently-dropped send, not a leak to anyone else).
  void stop() {
    _tokenRefreshSub?.cancel();
    _foregroundSub?.cancel();
    _openedAppSub?.cancel();
    _tokenRefreshSub = null;
    _foregroundSub = null;
    _openedAppSub = null;
  }
}
