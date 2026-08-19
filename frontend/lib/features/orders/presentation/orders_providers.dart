import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/orders_repository.dart';
import '../domain/invoice_model.dart';
import '../domain/live_tracking_model.dart';
import '../domain/order_models.dart';

final ordersRepositoryProvider = Provider<OrdersRepository>((ref) {
  return OrdersRepository(apiClient: ref.watch(apiClientProvider));
});

typedef MyOrdersPage = ({List<OrderSummary> orders, int page, int totalPages});

/// Paginated - a repeat customer's order history has no natural upper bound,
/// so this loads one page at a time instead of the whole history at once.
/// AsyncNotifier (not a plain FutureProvider) so loadMore() can append to
/// the existing state, and so ref.invalidate(myOrdersProvider) elsewhere
/// (e.g. after cancelling an order) still resets it back to page 0.
class MyOrdersController extends AutoDisposeAsyncNotifier<MyOrdersPage> {
  @override
  Future<MyOrdersPage> build() async {
    final result = await ref.read(ordersRepositoryProvider).getMyOrders(page: 0);
    return (orders: result.orders, page: 0, totalPages: result.totalPages);
  }

  bool get hasMore {
    final current = state.valueOrNull;
    return current != null && current.page + 1 < current.totalPages;
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.page + 1 >= current.totalPages) return;

    final nextPage = current.page + 1;
    final result = await ref.read(ordersRepositoryProvider).getMyOrders(page: nextPage);
    state = AsyncData((
      orders: [...current.orders, ...result.orders],
      page: nextPage,
      totalPages: result.totalPages,
    ));
  }
}

final myOrdersProvider =
    AsyncNotifierProvider.autoDispose<MyOrdersController, MyOrdersPage>(MyOrdersController.new);

// autoDispose - a customer views many different orders' details over a
// session; no reason to keep every one cached once they've navigated away.
final orderDetailProvider = FutureProvider.autoDispose.family<OrderDetail, int>((ref, orderId) {
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
