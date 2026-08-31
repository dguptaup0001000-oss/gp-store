import '../../core/api/api_client.dart';
import 'store_operations_models.dart';

/// The admin console's client for the shop's operating controls.
///
/// NOTHING HERE DECIDES ANYTHING. Every call is refused by the server unless
/// the signed-in account holds DELIVERY_MANAGE (the packing list needs only
/// ORDERS_VIEW). Hiding a button in this app is a courtesy to the operator,
/// not a security boundary.
class StoreOperationsRepository {
  StoreOperationsRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<StoreOperations> getOperations() async {
    final response = await apiClient.dio.get('/api/admin/store/operations');
    return StoreOperations.fromJson(response.data as Map<String, dynamic>);
  }

  /// Sets AUTO / ON / OFF.
  ///
  /// Returns the whole operations payload rather than just the new state, so
  /// the card redraws from what the SERVER now believes instead of from what
  /// this app just asked for - the two differ if the write was rejected or
  /// somebody else changed it a second earlier.
  Future<StoreOperations> setAcceptance(
    StoreOrderAcceptance acceptance, {
    String? closureMessage,
  }) async {
    final response = await apiClient.dio.put(
      '/api/admin/store/operations',
      data: {
        'orderAcceptance': acceptance.wireName,
        if (closureMessage != null) 'closureMessage': closureMessage,
      },
    );
    return StoreOperations.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> addClosure(DateTime date, String? reason) async {
    await apiClient.dio.post('/api/admin/store/closures', data: {
      'date': _wire(date),
      if (reason != null && reason.isNotEmpty) 'reason': reason,
    });
  }

  Future<void> removeClosure(DateTime date) async {
    await apiClient.dio.delete('/api/admin/store/closures/${_wire(date)}');
  }

  /// One page of a day's packing list. Defaults to the next delivery day,
  /// which overnight is today's 09:00 run - what whoever arrives at 08:00
  /// actually wants.
  Future<PreparationList> getPreparation({DateTime? date, int page = 0}) async {
    final response = await apiClient.dio.get(
      '/api/admin/store/preparation',
      queryParameters: {
        if (date != null) 'date': _wire(date),
        'page': page,
      },
    );
    return PreparationList.fromJson(response.data as Map<String, dynamic>);
  }

  Future<List<DeliveryTypeShare>> getDeliveryTypeShares(int days) async {
    final response = await apiClient.dio.get(
      '/api/analytics/delivery-types',
      queryParameters: {'days': days},
    );
    return (response.data as List)
        .map((e) =>
            DeliveryTypeShare.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  static String _wire(DateTime date) =>
      '${date.year.toString().padLeft(4, '0')}-'
      '${date.month.toString().padLeft(2, '0')}-'
      '${date.day.toString().padLeft(2, '0')}';
}
