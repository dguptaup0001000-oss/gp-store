import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/router/app_router.dart';
import '../features/auth/presentation/auth_providers.dart';
import '../features/cart/presentation/cart_providers.dart';
import '../features/orders/presentation/order_detail_screen.dart';
import '../features/products/presentation/products_providers.dart';
import '../features/profile/presentation/profile_providers.dart';
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

  /// Report a finished foreground stretch, but only for a signed-in shopper.
  ///
  /// The endpoint is authenticated, so posting while signed out would be a
  /// 401 - and a 401 is what sends ApiClient into refresh-and-retry on a
  /// token that was never the problem. A telemetry number is never worth
  /// that, so an anonymous browse is simply not counted.
  static void _reportSession(WidgetRef ref, int seconds) {
    final signedIn = ref.read(authControllerProvider).status ==
        AuthStatus.authenticated;
    if (!signedIn) return;
    // Fire and forget: the repository swallows its own failures, and the
    // caller here is a lifecycle callback that must not await anything.
    ref.read(profileRepositoryProvider).reportAppSession(seconds);
  }

  @override
  Widget build(BuildContext context) {
    return GpstoreApp(
      title: 'GP-STORE',
      routerProvider: customerRouterProvider,
      onNotificationTap: _handleNotificationTap,
      onStaleResume: (ref) {
        ref.invalidate(cartControllerProvider);
        ref.invalidate(activeOffersProvider);
      },
      onSessionEnded: _reportSession,
    );
  }
}
