import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/order_group_repository.dart';
import '../domain/order_group_models.dart';

final orderGroupRepositoryProvider = Provider<OrderGroupRepository>((ref) {
  return OrderGroupRepository(apiClient: ref.watch(apiClientProvider));
});

/// Every checkout this customer has made, newest first as the server sends
/// them.
final myCheckoutsProvider = FutureProvider<List<OrderGroupSummary>>((ref) {
  return ref.watch(orderGroupRepositoryProvider).myCheckouts();
});

/// One checkout, re-read from the server rather than picked out of the list.
///
/// Statuses move while a customer is looking at their history, and a group
/// opened from a cached list would show what was true when the list loaded.
final orderGroupProvider =
    FutureProvider.autoDispose.family<OrderGroupSummary, int>((ref, groupId) {
  return ref.watch(orderGroupRepositoryProvider).checkout(groupId);
});

/// Which of this customer's orders belong to a checkout that spans more than
/// one shop, keyed by order id.
///
/// WHY IT IS DERIVED RATHER THAN CARRIED ON THE ORDER. OrderResponse has no
/// group id, and adding one would be a second copy of a fact the group
/// endpoint already answers. The history screen only needs to know "does this
/// row belong to a multi-shop checkout, and which one" - which is exactly
/// what this map says, from one request.
final checkoutForOrderProvider = Provider<Map<int, OrderGroupSummary>>((ref) {
  final checkouts = ref.watch(myCheckoutsProvider).valueOrNull;
  if (checkouts == null) return const {};
  final byOrderId = <int, OrderGroupSummary>{};
  for (final checkout in checkouts) {
    if (checkout.shopOrders.length < 2) continue;
    for (final shopOrder in checkout.shopOrders) {
      byOrderId[shopOrder.orderId] = checkout;
    }
  }
  return byOrderId;
});
