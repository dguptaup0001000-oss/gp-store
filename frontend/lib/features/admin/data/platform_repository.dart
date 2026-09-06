import '../../../core/api/api_client.dart';
import '../domain/platform_models.dart';

/// The platform's own surface: merchants, shops, and the market.
///
/// EVERY ROUTE HERE REQUIRES PERM_PLATFORM_ADMIN, enforced server-side. That
/// is not a permission any shop can grant - RolePermissions builds each shop
/// role by SUBTRACTING it - so a shop owner holding every permission their
/// own shop can give is still refused. This app therefore does no gating of
/// its own beyond hiding the entry point: a 403 from these calls is the real
/// answer and must be shown, not worked around.
class PlatformRepository {
  PlatformRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<MerchantView>> merchants() async {
    final response = await apiClient.dio.get('/api/platform/merchants');
    return _list(response.data, MerchantView.fromJson);
  }

  Future<MerchantView> merchant(int id) async {
    final response = await apiClient.dio.get('/api/platform/merchants/$id');
    return MerchantView.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// Moves a merchant through its lifecycle.
  ///
  /// THE REASON IS NOT OPTIONAL IN PRACTICE. "Somebody looked at this
  /// business's papers" is a real event, and a status change with no reason
  /// recorded is one nobody can account for later.
  Future<MerchantView> setMerchantStatus({
    required int merchantId,
    required String status,
    String? reason,
  }) async {
    final response = await apiClient.dio.put(
      '/api/platform/merchants/$merchantId/status',
      data: {'status': status, if (reason != null) 'reason': reason},
    );
    return MerchantView.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  Future<List<PlatformShopView>> shops({int? merchantId}) async {
    final response = await apiClient.dio.get(
      '/api/platform/shops',
      queryParameters: {if (merchantId != null) 'merchantId': merchantId},
    );
    return _list(response.data, PlatformShopView.fromJson);
  }

  Future<PlatformShopView> setShopStatus({
    required int shopId,
    required String status,
    String? reason,
  }) async {
    final response = await apiClient.dio.put(
      '/api/platform/shops/$shopId/status',
      data: {'status': status, if (reason != null) 'reason': reason},
    );
    return PlatformShopView.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  Future<MarketOverview> overview({int days = 30}) async {
    final response = await apiClient.dio.get(
      '/api/platform/overview',
      queryParameters: {'days': days},
    );
    return MarketOverview.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  static List<T> _list<T>(dynamic data, T Function(Map<String, dynamic>) parse) {
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((e) => parse(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }
}
