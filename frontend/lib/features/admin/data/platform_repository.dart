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

  /// Registers a business. It is NOT a shop yet and cannot trade.
  ///
  /// LANDS IN `APPLICATION`, not `APPROVED`, and the server decides that - no
  /// field here can shortcut it. A merchant created already trading is a
  /// merchant nobody checked, so the console has to walk the same
  /// review sequence anybody else would.
  ///
  /// [ownerCustomerId] IS THE FIELD THAT MATTERS MOST and the easiest to
  /// leave out. `ShopLifecycleService.open` grants it a staff row on every
  /// shop opened under this merchant, and makes that shop their default -
  /// so a merchant registered without one produces shops no one can sign in
  /// to. Recoverable later with [addStaff], but far cheaper to get right here.
  Future<MerchantView> registerMerchant({
    required String legalName,
    String? displayName,
    String? contactPhone,
    String? contactEmail,
    int? ownerCustomerId,
    bool demo = false,
  }) async {
    final response = await apiClient.dio.post(
      '/api/platform/merchants',
      data: {
        'legalName': legalName,
        if (displayName != null && displayName.isNotEmpty) 'displayName': displayName,
        if (contactPhone != null && contactPhone.isNotEmpty) 'contactPhone': contactPhone,
        if (contactEmail != null && contactEmail.isNotEmpty) 'contactEmail': contactEmail,
        if (ownerCustomerId != null) 'ownerCustomerId': ownerCustomerId,
        'demo': demo,
      },
    );
    return MerchantView.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// Opens a storefront under an already-approved merchant.
  ///
  /// ARRIVES AS `DRAFT`, deliberately: a shop is built before it sells, and
  /// the platform moves it to `ACTIVE` when it is ready. The server refuses
  /// this outright unless the merchant is `APPROVED` or `ACTIVE`, and refuses
  /// a [code] another shop already uses - both are conflicts worth showing
  /// verbatim rather than pre-empting, because the merchant's status can
  /// change between this form opening and being submitted.
  ///
  /// The shop's operating settings and delivery pricing rows are created
  /// server-side with it, so nothing here has to remember them.
  Future<PlatformShopView> openShop({
    required int merchantId,
    required String code,
    String? displayName,
    double? latitude,
    double? longitude,
    double? maxDeliveryRadiusKm,
    String? timeZone,
  }) async {
    final response = await apiClient.dio.post(
      '/api/platform/shops',
      data: {
        'merchantId': merchantId,
        'code': code,
        if (displayName != null && displayName.isNotEmpty) 'displayName': displayName,
        if (latitude != null) 'latitude': latitude,
        if (longitude != null) 'longitude': longitude,
        if (maxDeliveryRadiusKm != null) 'maxDeliveryRadiusKm': maxDeliveryRadiusKm,
        if (timeZone != null && timeZone.isNotEmpty) 'timeZone': timeZone,
      },
    );
    return PlatformShopView.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// Puts an account on a shop's staff list, which is the only way an account
  /// gets a tenant scope at all.
  ///
  /// [asDefault] IS AN INSTRUCTION, NOT A HINT. Without it an account that
  /// already has a home shop keeps it, so a merchant opening their second
  /// storefront would be added and land nowhere new.
  Future<void> addStaff({
    required int shopId,
    required int customerId,
    bool asDefault = true,
  }) async {
    await apiClient.dio.post(
      '/api/platform/shops/$shopId/staff',
      data: {'customerId': customerId, 'asDefault': asDefault},
    );
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
