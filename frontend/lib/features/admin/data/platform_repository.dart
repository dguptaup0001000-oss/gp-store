import '../../../core/api/api_client.dart';
import '../domain/platform_models.dart';
import '../domain/control_tower_models.dart';

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

  Future<PlatformSearchPage> globalSearch({
    required String query,
    int page = 0,
    int size = 20,
  }) async {
    final response = await apiClient.dio.get(
      '/api/platform/control/search',
      queryParameters: {'q': query, 'page': page, 'size': size},
    );
    return PlatformSearchPage.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }

  Future<PlatformDashboardSummary> controlTowerDashboard({
    required DateTime from,
    required DateTime to,
  }) async {
    String date(DateTime value) =>
        '${value.year.toString().padLeft(4, '0')}-'
        '${value.month.toString().padLeft(2, '0')}-'
        '${value.day.toString().padLeft(2, '0')}';
    final response = await apiClient.dio.get(
      '/api/platform/control/dashboard',
      queryParameters: {'from': date(from), 'to': date(to)},
    );
    return PlatformDashboardSummary.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }

  Future<Map<String, dynamic>> controlTowerEntity({
    required String entityType,
    required int entityId,
  }) async {
    final segment = switch (entityType.toUpperCase()) {
      'CUSTOMER' => 'customers',
      'MERCHANT' => 'merchants',
      'SHOP' => 'shops',
      _ => throw ArgumentError('No 360 view for $entityType'),
    };
    final response =
        await apiClient.dio.get('/api/platform/control/$segment/$entityId');
    return Map<String, dynamic>.from(response.data as Map);
  }

  Future<String?> revealCustomerPii({
    required int customerId,
    required String field,
    required String reason,
  }) async {
    final response = await apiClient.dio.post(
      '/api/platform/control/customers/$customerId/reveal',
      data: {'field': field, 'reason': reason},
    );
    final body = Map<String, dynamic>.from(response.data as Map);
    return body['value']?.toString();
  }

  Future<PlatformResourcePage> controlTowerResource({
    required String resource,
    String query = '',
    int page = 0,
    int size = 25,
  }) async {
    final response = await apiClient.dio.get(
      '/api/platform/control/$resource',
      queryParameters: {
        if (query.trim().isNotEmpty) 'q': query.trim(),
        'page': page,
        'size': size,
      },
    );
    return PlatformResourcePage.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }

  Future<Map<String, dynamic>> controlTowerOrder(int orderId) async {
    final response =
        await apiClient.dio.get('/api/platform/control/orders/$orderId');
    return Map<String, dynamic>.from(response.data as Map);
  }

  Future<Map<String, dynamic>> systemHealth() async {
    final responses = await Future.wait([
      apiClient.dio.get('/api/admin/ops/status'),
      apiClient.dio.get('/api/version'),
    ]);
    return {
      'health': Map<String, dynamic>.from(responses[0].data as Map),
      'version': Map<String, dynamic>.from(responses[1].data as Map),
    };
  }

  Future<List<MerchantView>> merchants() async {
    final response = await apiClient.dio.get('/api/platform/merchants');
    return _list(response.data, MerchantView.fromJson);
  }

  Future<MerchantView> merchant(int id) async {
    final response = await apiClient.dio.get('/api/platform/merchants/$id');
    return MerchantView.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// One merchant and every shop under it, in one call.
  ///
  /// THE SHOPS COME BACK KEYED BY THE MERCHANT ON THE SERVER, read by merchant
  /// id from the database rather than filtered from anything this app sent.
  /// There is no parameter here a client could point at another merchant's
  /// storefronts - the only id in the request is the merchant already being
  /// looked at, and PERM_PLATFORM_ADMIN is what allows looking at any of them.
  Future<PlatformMerchantDetail> merchantDetail(int merchantId) async {
    final response =
        await apiClient.dio.get('/api/platform/merchants/$merchantId/detail');
    return PlatformMerchantDetail.fromJson(
        Map<String, dynamic>.from(response.data as Map));
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

  /// Opens a staff login and returns the ONLY copy of its password.
  ///
  /// THE HOLE THIS FILLS. Until now no API could make an account an ADMIN:
  /// the only role ever assigned in code was DELIVERY_BOY, so onboarding a
  /// merchant meant SQL on the box. `registerMerchant` wants an
  /// `ownerCustomerId` that had to already exist, and nothing could create
  /// it.
  ///
  /// THE PASSWORD COMES BACK ONCE AND IS NOT STORED ANYWHERE. What the
  /// server keeps is a bcrypt hash, like every other password; the plaintext
  /// exists in this response and in no column, log or second table. Show it,
  /// let it be copied, and lose it - [resetStaffPassword] makes a new one if
  /// it is lost before being handed over.
  ///
  /// The account arrives owing a password change, so until the merchant sets
  /// their own the server refuses them every route but the change itself.
  /// After that the platform owner's copy is dead, which is what keeps the
  /// merchant's actions their own in a dispute.
  Future<OpenedStaffAccount> openStaffAccount({
    required String fullName,
    required String email,
    String? mobileNumber,
    required String role,
  }) async {
    final response = await apiClient.dio.post(
      '/api/platform/staff',
      data: {
        'fullName': fullName,
        'email': email,
        if (mobileNumber != null && mobileNumber.isNotEmpty) 'mobileNumber': mobileNumber,
        'role': role,
      },
    );
    return OpenedStaffAccount.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }

  /// Issues a new one-time password and kills every session the account has.
  ///
  /// NOT A WAY TO READ THE OLD ONE. There is no such route, deliberately:
  /// a merchant who cannot get in needs a new password, not the platform
  /// owner reading their current one.
  Future<OpenedStaffAccount> resetStaffPassword({required int customerId}) async {
    final response = await apiClient.dio.post(
      '/api/platform/staff/$customerId/reset-password',
    );
    return OpenedStaffAccount.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }

  /// Onboards a merchant in ONE call: login, business, review, shop.
  ///
  /// WHY NOT FIVE CALLS FROM HERE. Opening a merchant by hand is a staff
  /// login, a business, two status transitions and a shop. Done from the
  /// phone that is five round trips with four places to stop halfway, and
  /// every stop leaves something real behind - a login nobody can use, a
  /// business stuck in APPLICATION, a merchant with no shop. The server does
  /// it in one transaction, so the answer is a merchant who can be handed
  /// their password, or nothing.
  ///
  /// [latitude], [longitude] and [maxDeliveryRadiusKm] are REQUIRED here
  /// though openShop allows them to be null: the marketplace matches
  /// customers to shops by distance, so a shop without them looks finished
  /// and is offered to nobody.
  ///
  /// It stops short of letting them trade. The shop has empty shelves, and a
  /// findable shop with nothing to sell is worse than one not yet findable.
  Future<OnboardedMerchant> onboardMerchant({
    required String businessName,
    required String ownerName,
    required String ownerEmail,
    String? ownerPhone,
    String? shopCode,
    required double latitude,
    required double longitude,
    required double maxDeliveryRadiusKm,
    String? timeZone,
  }) async {
    final response = await apiClient.dio.post(
      '/api/platform/onboard',
      data: {
        'businessName': businessName,
        'ownerName': ownerName,
        'ownerEmail': ownerEmail,
        if (ownerPhone != null && ownerPhone.isNotEmpty) 'ownerPhone': ownerPhone,
        if (shopCode != null && shopCode.isNotEmpty) 'shopCode': shopCode,
        'latitude': latitude,
        'longitude': longitude,
        'maxDeliveryRadiusKm': maxDeliveryRadiusKm,
        if (timeZone != null && timeZone.isNotEmpty) 'timeZone': timeZone,
      },
    );
    return OnboardedMerchant.fromJson(
        Map<String, dynamic>.from(response.data as Map));
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

  /// A new activation code, killing the old one (§30).
  ///
  /// THERE IS NO "READ THE CODE" CALL, deliberately: it is stored as a
  /// fingerprint, so "I lost it" and "it leaked" have the same answer.
  Future<OpenedStaffAccount> reissueActivationCode({
    required int customerId,
    String? reason,
  }) async {
    final response = await apiClient.dio.post(
      '/api/platform/staff/$customerId/reissue-activation-code',
      data: {if (reason != null && reason.isNotEmpty) 'reason': reason},
    );
    return OpenedStaffAccount.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }
}
