import 'dart:async';

import 'package:flutter/widgets.dart';

import '../api/api_client.dart';
import '../logging/app_log.dart';
import 'admin_order_sound_poll.dart';
import 'push_notification_service.dart';
import 'voice_announcement_service.dart';

/// Polls for new admin orders and plays the soundbox (ding + spoken line)
/// while the shop app is open.
///
/// WHY THIS EXISTS IN ADDITION TO FCM. The sideload APK is built without a
/// real google-services.json, so Firebase never starts and NEW_ORDER pushes
/// never arrive. The counter phone is open as ADMIN; polling a cheap
/// afterId endpoint still announces the order. When FCM is configured, this
/// is a second path to the same VoiceAnnouncementService, which dedupes by
/// order id so a push and a poll cannot double-speak.
class AdminOrderSoundWatcher with WidgetsBindingObserver {
  AdminOrderSoundWatcher({
    required this.apiClient,
    required this.voice,
    required this.push,
    this.poll = const Duration(seconds: 4),
  });

  final ApiClient apiClient;
  final VoiceAnnouncementService voice;
  final PushNotificationService push;
  final Duration poll;

  final AdminOrderSoundPoll _state = AdminOrderSoundPoll();
  Timer? _timer;
  bool _inFlight = false;

  bool get isRunning => _timer != null;

  void start() {
    if (_timer != null) return;
    WidgetsBinding.instance.addObserver(this);
    _tick();
    _timer = Timer.periodic(poll, (_) => _tick());
  }

  void stop() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    _timer = null;
    _inFlight = false;
    _state.reset();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _tick();
    }
  }

  Future<void> _tick() async {
    if (_inFlight) return;
    _inFlight = true;
    try {
      final firstCall = !_state.armed;
      final response = await apiClient.dio.get(
        '/api/orders/admin/since',
        queryParameters: firstCall
            ? const <String, dynamic>{}
            : <String, dynamic>{'afterId': _state.afterId},
      );
      final data = response.data;
      if (data is! Map) return;
      final afterIdRaw = data['afterId'];
      final afterId = afterIdRaw is num
          ? afterIdRaw.toInt()
          : int.tryParse('$afterIdRaw');
      if (afterId == null) return;
      final rawOrders = data['orders'];
      final orders = <AdminNewOrderAlert>[];
      if (rawOrders is List) {
        for (final row in rawOrders) {
          if (row is Map<String, dynamic>) {
            orders.add(AdminNewOrderAlert.fromJson(row));
          } else if (row is Map) {
            orders.add(AdminNewOrderAlert.fromJson(
                row.map((key, value) => MapEntry(key.toString(), value))));
          }
        }
      }
      final toAnnounce = _state.ingest(
        responseAfterId: afterId,
        orders: orders,
        firstCall: firstCall,
      );
      for (final alert in toAnnounce) {
        await push.alertNewOrder(
          title: 'New order received from ${alert.customerName}',
          body: 'Order amount ₹${alert.orderAmount}',
        );
        await voice.announceNewOrder(
          orderId: alert.orderId,
          customerName: alert.customerName,
          rupees: alert.orderAmount,
        );
      }
    } catch (e) {
      appLog('Admin order sound poll failed (order itself is unaffected): $e');
    } finally {
      _inFlight = false;
    }
  }
}
