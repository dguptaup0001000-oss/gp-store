import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/router/app_router.dart';
import '../features/orders/presentation/order_detail_screen.dart';
import '../shared/gpstore_app.dart';
import 'customer_router.dart';

/// Shop APK UI. Does not import admin screens, printers, or order-voice.
class CustomerApp extends StatelessWidget {
  const CustomerApp({super.key});

  static void _handleNotificationTap(WidgetRef ref, RemoteMessage message) {
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

  @override
  Widget build(BuildContext context) {
    return GpstoreApp(
      title: 'GP-STORE',
      routerProvider: customerRouterProvider,
      onNotificationTap: _handleNotificationTap,
    );
  }
}
