import '../../../core/api/api_client.dart';
import '../domain/shop_admin_models.dart';

/// A shopkeeper's own shop.
///
/// NOT ONE ROUTE HERE TAKES A SHOP ID, and that is the security design rather
/// than an omission in this class. Every call acts on "the shop this request
/// is for", which the backend established from the credential before the
/// controller ran. There is no id for a caller to change, so there is nothing
/// for this app to have to guard.
class ShopSelfServiceRepository {
  ShopSelfServiceRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<ShopProfile> profile() async {
    final response = await apiClient.dio.get('/api/shop/profile');
    return ShopProfile.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// What still stands between this shop and its first order.
  Future<ShopReadiness> readiness() async {
    final response = await apiClient.dio.get('/api/shop/readiness');
    return ShopReadiness.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  Future<ShopEarnings> earnings({int days = 30}) async {
    final response = await apiClient.dio.get(
      '/api/shop/earnings',
      queryParameters: {'days': days},
    );
    return ShopEarnings.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// Orders by status, for this shop only - "what needs attention now".
  Future<Map<String, int>> openWork() async {
    final response = await apiClient.dio.get('/api/shop/open-work');
    final data = response.data;
    if (data is! Map) return const {};
    return data.map((key, value) =>
        MapEntry(key.toString(), value is num ? value.toInt() : 0));
  }

  /// Every shop this account may work in.
  ///
  /// ASKED EVERY TIME, NOT REMEMBERED (§33). The authoritative list is the
  /// server's, and a shop that was taken away must stop appearing the moment
  /// it is taken away rather than the next time somebody reinstalls.
  Future<MyShops> myShops() async {
    final response = await apiClient.dio.get('/api/shop/my-shops');
    return MyShops.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// The business behind the current shop. Owner only - the server refuses
  /// anybody else, including a manager of this very shop.
  Future<MerchantProfile> merchantProfile() async {
    final response = await apiClient.dio.get('/api/shop/merchant');
    return MerchantProfile.fromJson(Map<String, dynamic>.from(response.data as Map));
  }
}
