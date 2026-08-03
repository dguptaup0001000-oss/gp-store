import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/orders_repository.dart';
import '../domain/invoice_model.dart';
import '../domain/live_tracking_model.dart';
import '../domain/order_models.dart';

final ordersRepositoryProvider = Provider<OrdersRepository>((ref) {
  return OrdersRepository(apiClient: ref.watch(apiClientProvider));
});

final myOrdersProvider = FutureProvider<List<OrderSummary>>((ref) {
  return ref.watch(ordersRepositoryProvider).getMyOrders();
});

final orderDetailProvider = FutureProvider.family<OrderDetail, int>((ref, orderId) {
  return ref.watch(ordersRepositoryProvider).getOrderDetail(orderId);
});

final orderInvoiceProvider = FutureProvider.family<Invoice, int>((ref, orderId) {
  return ref.watch(ordersRepositoryProvider).getInvoiceForOrder(orderId);
});

// Not auto-refreshing on its own - the widget that watches this (see
// _DeliveryTrackingCard) drives re-fetches on a timer via ref.invalidate,
// only while the order is actually OUT_FOR_DELIVERY.
final liveTrackingProvider = FutureProvider.family<LiveDeliveryLocation, int>((ref, orderId) {
  return ref.watch(ordersRepositoryProvider).getLiveTracking(orderId);
});
