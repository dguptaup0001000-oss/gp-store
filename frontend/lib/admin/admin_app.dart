import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/logging/app_log.dart';
import '../core/notifications/admin_order_sound_providers.dart';
import '../core/notifications/voice_announcement_providers.dart';
import '../core/printing/printer_providers.dart';
import '../core/router/app_router.dart';
import '../features/admin/presentation/admin_order_detail_screen.dart';
import '../features/orders/presentation/orders_providers.dart';
import '../shared/gpstore_app.dart';
import 'design/admin_theme.dart';
import 'admin_router.dart';

/// Staff APK UI. Does not import the customer shopping shell.
class AdminApp extends StatelessWidget {
  const AdminApp({super.key});

  static void _handleNotificationTap(WidgetRef ref, RemoteMessage message) {
    final type = message.data['type'];
    final orderIdRaw = message.data['orderId'];
    if (orderIdRaw == null) return;
    if (type != 'NEW_ORDER' && type != 'ORDER_STATUS') return;

    final orderId = int.tryParse(orderIdRaw);
    if (orderId == null) return;

    final navigator = rootNavigatorKey.currentState;
    if (navigator == null) return;

    navigator.push(MaterialPageRoute(
        builder: (_) => AdminOrderDetailScreen(orderId: orderId)));
  }

  static Future<void> _autoPrintIfNewOrder(
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

  static void _announceIfNewOrder(WidgetRef ref, RemoteMessage message) {
    if (message.data['type'] != 'NEW_ORDER') return;

    final orderId = message.data['orderId'];
    final customerName = message.data['customerName'];
    final orderAmount = message.data['orderAmount'];
    if (orderId == null || customerName == null || orderAmount == null) {
      return;
    }

    ref.read(voiceAnnouncementServiceProvider).announceNewOrder(
          orderId: orderId,
          customerName: customerName,
          rupees: orderAmount,
        );
  }

  static void _onForegroundExtras(WidgetRef ref, RemoteMessage message) {
    _autoPrintIfNewOrder(ref, message);
    _announceIfNewOrder(ref, message);
  }

  static void _onAdminSession(WidgetRef ref, String? role) {
    if (role == 'ADMIN') {
      ref.read(adminOrderSoundWatcherProvider).start();
    } else {
      ref.read(adminOrderSoundWatcherProvider).stop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return GpstoreApp(
      title: 'GP-STORE Admin',
      theme: AdminTheme.light,
      routerProvider: adminRouterProvider,
      onNotificationTap: _handleNotificationTap,
      onForegroundExtras: _onForegroundExtras,
      onAdminSession: _onAdminSession,
    );
  }
}
