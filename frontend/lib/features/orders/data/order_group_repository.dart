import '../../../core/api/api_client.dart';
import '../domain/order_group_models.dart';

/// The checkout a customer thinks they placed.
///
/// A BASKET SPANNING TWO SHOPS BECOMES TWO ORDERS, and the customer pressed
/// one button. The group is what they remember doing; the orders are what
/// each kirana sees. This reads the group so the app can show one entry
/// instead of two unexplained ones.
///
/// CANCELLING ANSWERS PER SHOP, and the app must render it that way rather
/// than collapsing it. One shop may still be packing while the other's rider
/// is at the door, so "cancel my order" genuinely succeeds for one and fails
/// for the other - the backend returns an outcome each, and flattening that
/// into a single success or failure would tell the customer something untrue.
class OrderGroupRepository {
  OrderGroupRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<OrderGroupSummary>> myCheckouts() async {
    final response = await apiClient.dio.get('/api/orders/groups');
    final data = response.data;
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((e) => OrderGroupSummary.fromJson(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }

  Future<OrderGroupSummary> checkout(int groupId) async {
    final response = await apiClient.dio.get('/api/orders/groups/$groupId');
    return OrderGroupSummary.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// Cancels every shop's order in a checkout that can still be cancelled.
  ///
  /// The result is per shop and partial success is normal - see the class
  /// comment. Callers must show the outcomes, not a single verdict.
  Future<OrderGroupCancelResult> cancelWholeCheckout(int groupId) async {
    final response = await apiClient.dio.put('/api/orders/groups/$groupId/cancel');
    return OrderGroupCancelResult.fromJson(Map<String, dynamic>.from(response.data as Map));
  }
}
