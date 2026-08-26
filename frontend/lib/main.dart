import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/lifecycle/session_refresh.dart';
import 'core/logging/app_log.dart';
import 'core/monitoring/crash_reporter.dart';
import 'core/monitoring/firebase_crash_reporter.dart';
import 'core/notifications/push_notification_providers.dart';
import 'core/notifications/push_notification_service.dart';
import 'core/notifications/voice_announcement_providers.dart';
import 'core/printing/printer_providers.dart';
import 'core/router/app_router.dart';
import 'core/theme/app_theme.dart';
import 'features/auth/presentation/auth_providers.dart';
import 'features/orders/presentation/order_detail_screen.dart';
import 'features/orders/presentation/orders_providers.dart';

/// Lets code without a local BuildContext (the FCM tap handler below) still
/// show a SnackBar on top of whatever the user is currently looking at.
/// (rootNavigatorKey, for pushing whole screens, is imported above from
/// app_router.dart - see that file's doc comment for why it lives there.)
final scaffoldMessengerKey = GlobalKey<ScaffoldMessengerState>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  try {
    await Firebase.initializeApp();

    // FIRST, and before runApp: from here on a framework error or an
    // uncaught async error is recorded instead of painting a grey box on a
    // customer's phone that nobody ever hears about. It cannot run any
    // earlier than this - Crashlytics needs the Firebase app - so a failure
    // in initializeApp itself is still only a log line, which is the honest
    // limit of this and is why it sits at the very top of the try.
    await installCrashHandlers(const FirebaseCrashReporter());

    // Must be registered before runApp(), and must reference a top-level
    // function (see push_notification_service.dart's doc comment on why).
    FirebaseMessaging.onBackgroundMessage(firebaseBackgroundMessageHandler);
  } catch (e) {
    // No google-services.json yet, or no Firebase project configured -
    // this is expected until FIREBASE_SETUP.md's one-time setup is done.
    // The app must still run and be fully usable without push or crash
    // reporting in the meantime, so this is deliberately swallowed, not
    // rethrown.
    appLog(
        'Firebase not configured yet - push and crash reporting disabled: $e');
    await installCrashHandlers(const NoOpCrashReporter());
  }

  runApp(const ProviderScope(child: GpStoreApp()));
}

class GpStoreApp extends ConsumerWidget {
  const GpStoreApp({super.key});

  /// Routes a tapped notification to somewhere useful instead of just
  /// opening the app to its default home screen. Only ORDER_STATUS needs
  /// special handling - a NEW_ASSIGNMENT tap already lands a delivery
  /// partner on their dashboard by default (see RootScreen), which already
  /// shows the new assignment once the list refreshes.
  void _handleNotificationTap(WidgetRef ref, RemoteMessage message) {
    final type = message.data['type'];
    final orderIdRaw = message.data['orderId'];

    if (type == 'ORDER_STATUS' && orderIdRaw != null) {
      final orderId = int.tryParse(orderIdRaw);
      if (orderId == null) return;

      final navigator = rootNavigatorKey.currentState;
      if (navigator == null) return;

      navigator.push(MaterialPageRoute(
          builder: (_) => OrderDetailScreen(orderId: orderId)));
    }
  }

  /// The store-owner side of a new order arriving - see
  /// NotificationService.notifyAdminsOfNewOrder on the backend, which is
  /// the only thing that ever sends a NEW_ORDER push (customer accounts
  /// never receive this type). Fetches the order's full detail, then hands
  /// it to PrinterService - a no-op if no printer has been set up yet (see
  /// AdminPrinterSettingsScreen), and never throws into this handler either
  /// way. Only fires while the app is in the foreground; a killed/fully
  /// backgrounded app can't run Dart code to print, so this only covers the
  /// "app open on the counter" case, not true background printing.
  Future<void> _autoPrintIfNewOrder(
      WidgetRef ref, RemoteMessage message) async {
    if (message.data['type'] != 'NEW_ORDER') return;

    final orderIdRaw = message.data['orderId'];
    final orderId = orderIdRaw != null ? int.tryParse(orderIdRaw) : null;
    if (orderId == null) return;

    try {
      final order =
          await ref.read(ordersRepositoryProvider).getOrderDetail(orderId);
      await ref.read(printerServiceProvider).printOrderReceipt(order);
    } catch (e) {
      appLog(
          'Auto-print for order $orderId failed (order itself is unaffected): $e');
    }
  }

  /// Speaks a newly-arrived order aloud, soundbox style - the audible half of
  /// the same notification the banner shows.
  ///
  /// Reads customerName and orderAmount from the push's DATA, not from the
  /// notification title or body. The backend fills those fields from the
  /// committed order (see NotificationService.notifyAdminsOfNewOrder), so the
  /// spoken name and amount are the server's own, never the placing client's,
  /// and never recovered by picking apart a display string.
  ///
  /// Shares the NEW_ORDER trigger with auto-print above rather than
  /// introducing a second path, so the two can never disagree about which
  /// order arrived. Both are best-effort: this is called without await and
  /// swallows everything internally, so a mute phone or a missing TTS engine
  /// cannot affect the order, the banner, or the receipt.
  void _announceIfNewOrder(WidgetRef ref, RemoteMessage message) {
    if (message.data['type'] != 'NEW_ORDER') return;

    final orderId = message.data['orderId'];
    final customerName = message.data['customerName'];
    final orderAmount = message.data['orderAmount'];

    // An older backend that predates these fields simply stays silent -
    // the banner and the receipt still work exactly as before.
    //
    // orderId is required and not merely preferred: without it there is no
    // way to tell a redelivered push from a genuine second order placed by
    // the same customer for the same amount, and announcing on name+amount
    // would either double-speak one order or swallow a real one. Silence is
    // the correct behaviour for a push we cannot identify.
    if (orderId == null || customerName == null || orderAmount == null) return;

    ref.read(voiceAnnouncementServiceProvider).announceNewOrder(
          orderId: orderId,
          customerName: customerName,
          rupees: orderAmount,
        );
  }

  void _showForegroundBanner(RemoteMessage message) {
    final title = message.notification?.title;
    final body = message.notification?.body;
    if (title == null && body == null) return;

    scaffoldMessengerKey.currentState?.showSnackBar(
      SnackBar(
        content: Text(
            [title, body].where((s) => s != null && s.isNotEmpty).join(' - ')),
        duration: const Duration(seconds: 4),
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(appRouterProvider);

    // Starts push notification setup the moment someone is actually
    // logged in (covers fresh login, register, OTP login, AND session
    // restore on app relaunch - all of them flow through this same
    // AuthStatus.authenticated transition) and tears it down on logout.
    // A single hook here instead of scattering start()/stop() calls across
    // every place auth state can change.
    ref.listen<AuthState>(authControllerProvider, (previous, next) {
      final wasAuthenticated = previous?.status == AuthStatus.authenticated;
      final isAuthenticated = next.status == AuthStatus.authenticated;

      if (isAuthenticated && !wasAuthenticated) {
        ref.read(pushNotificationServiceProvider).start(
              onForegroundMessage: (message) {
                _showForegroundBanner(message);
                _autoPrintIfNewOrder(ref, message);
                _announceIfNewOrder(ref, message);
              },
              onNotificationTap: (message) =>
                  _handleNotificationTap(ref, message),
            );
      } else if (!isAuthenticated && wasAuthenticated) {
        ref.read(pushNotificationServiceProvider).stop();
      }
    });

    return SessionRefresh(
      child: MaterialApp.router(
        title: 'GP-Store',
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light,
        routerConfig: router,
        scaffoldMessengerKey: scaffoldMessengerKey,
      ),
    );
  }
}
