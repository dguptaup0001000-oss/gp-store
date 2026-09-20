import '../../features/marketplace/domain/marketplace_feed_models.dart';
import '../../features/marketplace/domain/marketplace_offer.dart';
import '../api/api_client.dart';
import 'marketplace_models.dart';

/// Reads the public marketplace surface, and the customer's own preferences.
///
/// EVERY DECISION HERE IS THE BACKEND'S. Which shops serve a point, how far
/// each is, whether a shop may be shown to customers at all, which shop is the
/// best deal, whether a farther shop is cheap enough to be worth it, what a
/// basket costs per shop - all of it is answered by the API and none of it is
/// recomputed in Dart. This class parses and nothing else, on purpose: a rule
/// evaluated in the app and the same rule evaluated on the server would
/// disagree the first time either changed, and the server's is the one that
/// decided what the customer was offered.
class MarketplaceRepository {
  MarketplaceRepository({required this.apiClient});

  final ApiClient apiClient;

  /// THE MARKETPLACE ITSELF: what is for sale near this customer, before
  /// they have chosen anybody's shop.
  ///
  /// NOT /api/products/feed, and the difference is the whole point. That
  /// route answers "what does the shop I am in sell" - it requires a listing,
  /// listings are shop-owned, and a customer who has chosen no shop resolves
  /// to Shop #1. So it showed one kirana's shelf on a screen labelled All
  /// Products, or nothing at all where that shop has no listings. This route
  /// asks about the TOWN, which is a different question and needed a
  /// different endpoint rather than a repair to that one.
  ///
  /// A MISSING PIN RETURNS EMPTY, deliberately. A customer whose address
  /// cannot be placed cannot be told which shops deliver to them, and
  /// inventing a location would show somebody in one town another town's
  /// shops. The caller draws "add an address to see what is nearby", which is
  /// an honest answer rather than an error.
  Future<List<MarketplaceCard>> feed({
    required double? latitude,
    required double? longitude,
    CommerceMode mode = CommerceMode.buyOnline,
    int? categoryId,
    int page = 0,
    int size = 20,
  }) async {
    if (latitude == null || longitude == null) return const [];
    final response = await apiClient.dio.get(
      '/api/marketplace/feed',
      queryParameters: {
        'lat': latitude,
        'lng': longitude,
        'mode': mode.wire,
        if (categoryId != null) 'categoryId': categoryId,
        'page': page,
        'size': size,
      },
    );
    final data = response.data;
    if (data is! List) return const [];
    return data
        .whereType<Map<String, dynamic>>()
        .map(MarketplaceCard.fromJson)
        .toList(growable: false);
  }

  /// Search the whole marketplace, across every mode.
  ///
  /// The product search routes are shop-scoped, so on a marketplace they can
  /// only ever answer about one shop. This asks what the TOWN sells, and it
  /// defaults to all three modes because somebody typing "haircut" wants the
  /// barber - a search restricted to what a cart can hold finds nothing.
  Future<List<MarketplaceCard>> search({
    required String query,
    required double? latitude,
    required double? longitude,
    CommerceMode? mode,
    int page = 0,
    int size = 20,
  }) async {
    if (latitude == null || longitude == null || query.trim().isEmpty) {
      return const [];
    }
    final response = await apiClient.dio.get(
      '/api/marketplace/search',
      queryParameters: {
        'q': query.trim(),
        'lat': latitude,
        'lng': longitude,
        if (mode != null) 'mode': mode.wire,
        'page': page,
        'size': size,
      },
    );
    final data = response.data;
    if (data is! List) return const [];
    return data
        .whereType<Map<String, dynamic>>()
        .map(MarketplaceCard.fromJson)
        .toList(growable: false);
  }

  /// Every nearby shop offering this product, nearest first.
  ///
  /// ALL THREE MODES COME BACK and the screen groups them. Filtering here by
  /// the mode the customer arrived through would hide a shop that delivers
  /// the thing from somebody looking at a Visit-to-Buy card - a narrower
  /// answer than the question they actually have.
  Future<ProductOffers> offersOf({
    required int productId,
    required double? latitude,
    required double? longitude,
  }) async {
    if (latitude == null || longitude == null) {
      return const ProductOffers([]);
    }
    final response = await apiClient.dio.get(
      '/api/marketplace/products/$productId/offers',
      queryParameters: {'lat': latitude, 'lng': longitude},
    );
    final data = response.data;
    if (data is! List) return const ProductOffers([]);
    return ProductOffers(data
        .whereType<Map<String, dynamic>>()
        .map(MarketplaceOffer.fromJson)
        .toList(growable: false));
  }

  /// Shops that will deliver to this point, nearest first.
  ///
  /// EMPTY IS AN ANSWER, NOT A FAILURE. Nobody delivering to a point is an
  /// ordinary state - a customer outside every shop's radius - and the screen
  /// says so rather than showing an error. A missing pin returns empty for
  /// the same reason: an address that cannot be proved deliverable is not
  /// deliverable, which is the rule the backend already applies.
  Future<List<Storefront>> shopsNear({double? latitude, double? longitude}) async {
    final response = await apiClient.dio.get(
      '/api/marketplace/shops',
      queryParameters: {
        if (latitude != null) 'lat': latitude,
        if (longitude != null) 'lng': longitude,
      },
    );
    final data = response.data;
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((e) => Storefront.fromJson(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }

  /// The same question with a "search farther" answer attached.
  ///
  /// LOCAL FIRST: with no radius this is exactly [shopsNear] - the shops that
  /// can actually deliver here. A radius is the second tap, and the server
  /// keeps climbing its own ladder until it finds something rather than
  /// answering an empty screen at each rung in turn.
  /// [categoryId] NARROWS EACH RUNG OF THE LADDER, not the answer. The server
  /// keeps climbing until it finds a radius that has a shop selling this
  /// category - so a customer whose nearest shops are all kiranas is shown the
  /// chemist eleven kilometres out rather than an empty screen they cannot
  /// widen their way off. Null asks the question the released app already
  /// asks, unchanged.
  Future<DiscoveryPage> discover({
    double? latitude,
    double? longitude,
    double? radiusKm,
    int? categoryId,
  }) async {
    final response = await apiClient.dio.get(
      '/api/marketplace/discovery',
      queryParameters: {
        if (latitude != null) 'lat': latitude,
        if (longitude != null) 'lng': longitude,
        if (radiusKm != null) 'radiusKm': radiusKm,
        if (categoryId != null) 'categoryId': categoryId,
      },
    );
    return DiscoveryPage.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// One storefront, with everything the decision to buy from it needs.
  ///
  /// Throws ApiException(404) when the marketplace does not show it - a draft,
  /// closed or suspended shop is indistinguishable from a shop that never
  /// existed, which is the backend's choice and not this app's to undo.
  Future<StorefrontDetail> storefront(int shopId) async {
    final response = await apiClient.dio.get('/api/marketplace/shops/$shopId');
    return StorefrontDetail.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// The categories somebody near this pin actually stocks.
  ///
  /// EMPTY IS AN ANSWER. A pin no shop serves has nothing to buy, and the home
  /// screen says so rather than drawing a catalogue that leads nowhere.
  Future<List<MarketCategory>> categoriesNear({
    required double latitude,
    required double longitude,
  }) async {
    final response = await apiClient.dio.get(
      '/api/marketplace/categories',
      queryParameters: {'lat': latitude, 'lng': longitude},
    );
    final data = response.data;
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((e) => MarketCategory.fromJson(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }

  Future<MarketplaceMode> mode() async {
    final response = await apiClient.dio.get('/api/marketplace/mode');
    return MarketplaceMode.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// Every shop's price for one item, ordered by Best Deal.
  Future<ShopComparison> compare({
    required int variantId,
    double? latitude,
    double? longitude,
    double? radiusKm,
  }) async {
    final response = await apiClient.dio.get(
      '/api/discovery/compare',
      queryParameters: {
        'variantId': variantId,
        if (latitude != null) 'lat': latitude,
        if (longitude != null) 'lng': longitude,
        if (radiusKm != null) 'radiusKm': radiusKm,
      },
    );
    return ShopComparison.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// The same offers, the customer's own order first.
  Future<PreferredFirstOffers> preferredFirst({
    required int variantId,
    required int categoryId,
    double? latitude,
    double? longitude,
    double? radiusKm,
  }) async {
    final response = await apiClient.dio.get(
      '/api/discovery/preferred',
      queryParameters: {
        'variantId': variantId,
        'categoryId': categoryId,
        if (latitude != null) 'lat': latitude,
        if (longitude != null) 'lng': longitude,
        if (radiusKm != null) 'radiusKm': radiusKm,
      },
    );
    return PreferredFirstOffers.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// This customer's chosen shops for one category.
  Future<PreferredShopChoice> preferredShops(int categoryId) async {
    final response = await apiClient.dio.get('/api/preferred-shops/$categoryId');
    return PreferredShopChoice.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// Replaces a category's choices. An empty list clears it - that is how
  /// "actually, no preference" is said, and it needs to be sayable.
  Future<PreferredShopChoice> setPreferredShops(int categoryId, List<int> shopIds) async {
    final response = await apiClient.dio.put(
      '/api/preferred-shops/$categoryId',
      data: {'shopIds': shopIds},
    );
    return PreferredShopChoice.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// The basket drawn as the several purchases it actually is.
  ///
  /// The address is what lets each shop quote delivery; without one every
  /// section says so rather than showing zero.
  Future<BasketByShop> basketByShop({int? addressId}) async {
    final response = await apiClient.dio.get(
      '/api/carts/mine/by-shop',
      queryParameters: {if (addressId != null) 'addressId': addressId},
    );
    return BasketByShop.fromJson(Map<String, dynamic>.from(response.data as Map));
  }
}
