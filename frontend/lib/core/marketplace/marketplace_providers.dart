import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/address/presentation/address_providers.dart';
import '../../features/auth/presentation/auth_providers.dart';
import '../../features/cart/presentation/cart_providers.dart';
import '../../features/orders/presentation/orders_providers.dart';
import '../../features/products/presentation/product_feed_provider.dart';
import '../../features/products/presentation/products_providers.dart';
import '../store/store_status_provider.dart';
import 'marketplace_models.dart';
import 'marketplace_repository.dart';
import 'shop_context.dart';

final marketplaceRepositoryProvider = Provider<MarketplaceRepository>((ref) {
  return MarketplaceRepository(apiClient: ref.watch(apiClientProvider));
});

/// Whether this deployment is a marketplace at all.
///
/// THE ONE GATE. Every marketplace affordance in the customer app hangs off
/// this and nothing else - no second mode flag, no counting how many shops
/// came back. It is the backend's `/api/marketplace/mode`, which exists for
/// exactly this question.
///
/// A FAILURE READS AS SINGLE SHOP, deliberately. If this call is refused, or
/// the app is talking to a backend too old to answer it, the customer gets
/// the shop they have always had rather than a switcher over a deployment
/// that has one shop. Being wrong in that direction costs a feature; being
/// wrong the other way breaks the working app.
final marketplaceModeProvider = FutureProvider<MarketplaceMode>((ref) async {
  try {
    return await ref.watch(marketplaceRepositoryProvider).mode();
  } catch (_) {
    return MarketplaceMode.singleShop;
  }
});

/// True only once the backend has SAID it is a marketplace.
///
/// Loading and error both read false, which is what keeps a slow or absent
/// answer from flashing marketplace UI onto a single-shop app.
final isMarketplaceProvider = Provider<bool>((ref) {
  return ref.watch(marketplaceModeProvider).valueOrNull?.multiShop ?? false;
});

/// The shops that will deliver to a point, nearest first.
///
/// STRAIGHT FROM THE BACKEND, IN THE BACKEND'S ORDER. ShopDiscovery decides
/// which shops serve a point - each shop's own radius, never a platform-wide
/// one - and returns them closest first. Nothing is re-sorted, re-filtered or
/// re-ranked here; a distance computed twice is a distance that can disagree
/// with itself.
final shopsNearProvider =
    FutureProvider.autoDispose.family<List<Storefront>, ({double lat, double lng})>(
        (ref, point) {
  return ref
      .watch(marketplaceRepositoryProvider)
      .shopsNear(latitude: point.lat, longitude: point.lng);
});

/// The pin the app asks "which shops deliver here?" with.
///
/// The customer's default address, or their first if none is marked default.
/// Null when they have no address yet, which is a real state on a fresh
/// install and the reason the picker offers to add one rather than showing
/// an empty list.
final deliveryPinProvider = Provider<({double lat, double lng})?>((ref) {
  final addresses = ref.watch(myAddressesProvider).valueOrNull;
  if (addresses == null || addresses.isEmpty) return null;
  final chosen = addresses.firstWhere(
    (address) => address.defaultAddress,
    orElse: () => addresses.first,
  );
  return (lat: chosen.latitude, lng: chosen.longitude);
});

/// The storefront the customer is currently shopping, when they have chosen
/// one. Null under a single shop, and null on a marketplace until they pick.
///
/// THE DETAIL, NOT THE LIST ROW. The rating, the trusted badge and the shop's
/// own promises are only on the detail response - the discovery list
/// deliberately does not pay for them - so this is what the shop profile
/// screen reads.
final selectedStorefrontProvider =
    FutureProvider.autoDispose<StorefrontDetail?>((ref) async {
  final shopId = ref.watch(shopContextProvider);
  if (shopId == null) return null;
  return ref.watch(marketplaceRepositoryProvider).storefront(shopId);
});

/// One storefront by id, for looking at a shop that is not the current one.
///
/// SEPARATE FROM [selectedStorefrontProvider] on purpose: opening a
/// competitor's profile to compare must not change which shop the app is
/// acting for. Switching is a deliberate act with its own button.
final storefrontProvider =
    FutureProvider.autoDispose.family<StorefrontDetail, int>((ref, shopId) {
  return ref.watch(marketplaceRepositoryProvider).storefront(shopId);
});

/// How far the customer has asked us to look, in km.
///
/// NULL IS LOCAL-FIRST and is where every customer starts: the shops that can
/// actually deliver to them. A value here means they pressed "search farther"
/// and the server is free to climb past it to the next rung that has anything
/// in it.
class DiscoveryRadius extends Notifier<double?> {
  @override
  double? build() => null;

  void searchFarther(double? nextRadiusKm) {
    if (nextRadiusKm == null) return;
    state = nextRadiusKm;
  }

  /// Back to the shops that deliver here.
  void backToLocal() => state = null;
}

final discoveryRadiusProvider =
    NotifierProvider<DiscoveryRadius, double?>(DiscoveryRadius.new);

/// Discovery: the shops, plus how much farther there is to look.
///
/// THE LADDER IS THE SERVER'S, INCLUDING THE WORDS. When the rung the customer
/// asked for was empty the server widens and says so in `message`; the screen
/// prints that sentence rather than composing its own, so the wording and the
/// behaviour cannot drift apart.
final discoveryProvider = FutureProvider.autoDispose
    .family<DiscoveryPage, ({double lat, double lng})>((ref, point) {
  final radiusKm = ref.watch(discoveryRadiusProvider);
  return ref.watch(marketplaceRepositoryProvider).discover(
        latitude: point.lat,
        longitude: point.lng,
        radiusKm: radiusKm,
      );
});

/// The categories this customer can actually buy from, most-stocked first.
///
/// THE HOME SCREEN'S CATEGORY SHELF. Not `categoriesProvider`, which is the
/// catalogue - every category the platform has defined, priced and listed by
/// whichever shop the app is currently acting for. This one is the
/// marketplace's answer to "what can I buy here", so a town with four kiranas
/// and a chemist is offered groceries and medicine rather than twenty doors
/// that open onto nothing.
///
/// NULL PIN MEANS NO ANSWER, NOT AN EMPTY ONE. A customer with no address yet
/// cannot be told which categories serve them, and the screen falls back to
/// the catalogue rather than showing them an empty marketplace.
final marketCategoriesProvider =
    FutureProvider.autoDispose<List<MarketCategory>>((ref) async {
  final pin = ref.watch(deliveryPinProvider);
  if (pin == null) return const [];
  return ref
      .watch(marketplaceRepositoryProvider)
      .categoriesNear(latitude: pin.lat, longitude: pin.lng);
});

/// The shops near this customer that sell one category, nearest first.
///
/// THE RADIUS IS SHARED WITH [discoveryProvider] on purpose. "Search farther"
/// is one idea in this app, and a customer who widened the search on the shop
/// picker has said something about how far they are willing to look that stays
/// true when they open a category.
///
/// EVERY DECISION IS THE SERVER'S: which shops serve the pin, which of them
/// stock the category, what order they come in, and when to climb to the next
/// rung. Nothing here re-sorts or re-filters the list.
final categoryShopsProvider = FutureProvider.autoDispose
    .family<DiscoveryPage, int>((ref, categoryId) {
  final pin = ref.watch(deliveryPinProvider);
  if (pin == null) return Future.value(const DiscoveryPage());
  final radiusKm = ref.watch(discoveryRadiusProvider);
  return ref.watch(marketplaceRepositoryProvider).discover(
        latitude: pin.lat,
        longitude: pin.lng,
        radiusKm: radiusKm,
        categoryId: categoryId,
      );
});

/// The address a delivery quote is worked out against.
///
/// The same address [deliveryPinProvider] uses, by id rather than by point -
/// the basket needs the row, not the coordinates, because a shop quotes
/// delivery against an address it can check is inside its circle.
final deliveryAddressIdProvider = Provider<int?>((ref) {
  final addresses = ref.watch(myAddressesProvider).valueOrNull;
  if (addresses == null || addresses.isEmpty) return null;
  return addresses
      .firstWhere((address) => address.defaultAddress, orElse: () => addresses.first)
      .id;
});

/// The basket, priced per shop by the server.
///
/// WHY NOT COMPUTED FROM THE CART RESPONSE: delivery is per shop, quoted by
/// that shop against this address, and discounts are per shop too. Adding the
/// lines up in Dart would produce a number no shop agreed to and that
/// checkout would then contradict.
final basketByShopProvider = FutureProvider.autoDispose<BasketByShop>((ref) {
  // Re-reads whenever the basket itself changes, so a quantity edit on the
  // cart screen updates the per-shop totals under it.
  ref.watch(cartControllerProvider);
  final addressId = ref.watch(deliveryAddressIdProvider);
  return ref.watch(marketplaceRepositoryProvider).basketByShop(addressId: addressId);
});

/// Every shop's price for one item, ordered by Best Deal.
final shopComparisonProvider = FutureProvider.autoDispose
    .family<ShopComparison, int>((ref, variantId) {
  final pin = ref.watch(deliveryPinProvider);
  final radiusKm = ref.watch(discoveryRadiusProvider);
  return ref.watch(marketplaceRepositoryProvider).compare(
        variantId: variantId,
        latitude: pin?.lat,
        longitude: pin?.lng,
        radiusKm: radiusKm,
      );
});

/// This customer's chosen shops for one category, and the cap on them.
final preferredShopsProvider =
    FutureProvider.autoDispose.family<PreferredShopChoice, int>((ref, categoryId) {
  return ref.watch(marketplaceRepositoryProvider).preferredShops(categoryId);
});

/// The same offers with the customer's own shops first.
///
/// WATCHES THE PREFERENCES IT IS ORDERED BY. Starring a shop changes what
/// this list means, so the dependency is declared rather than left to each
/// caller to remember to invalidate - a list that kept saying "distance
/// order" straight after the customer chose a shop would read as the choice
/// not having been saved.
final preferredFirstOffersProvider = FutureProvider.autoDispose
    .family<PreferredFirstOffers, ({int variantId, int categoryId})>((ref, key) {
  ref.watch(preferredShopsProvider(key.categoryId));
  final pin = ref.watch(deliveryPinProvider);
  return ref.watch(marketplaceRepositoryProvider).preferredFirst(
        variantId: key.variantId,
        categoryId: key.categoryId,
        latitude: pin?.lat,
        longitude: pin?.lng,
      );
});

/// Adds or removes a shop from a category's preferences.
///
/// THE CAP IS THE SERVER'S. `maxPerCategory` comes back on every read and the
/// server refuses a third shop regardless; this reads the current list, edits
/// it, and sends the whole list back, which is the shape the endpoint takes.
/// Nothing here decides what the limit is.
class PreferredShopsEditor {
  const PreferredShopsEditor(this._ref);

  final Ref _ref;

  Future<PreferredShopChoice> toggle(int categoryId, int shopId) async {
    final repo = _ref.read(marketplaceRepositoryProvider);
    final current = await repo.preferredShops(categoryId);
    final ids = List<int>.from(current.shopIds);
    if (ids.contains(shopId)) {
      ids.remove(shopId);
    } else {
      ids.add(shopId);
    }
    final saved = await repo.setPreferredShops(categoryId, ids);
    _ref.invalidate(preferredShopsProvider(categoryId));
    return saved;
  }
}

final preferredShopsEditorProvider =
    Provider<PreferredShopsEditor>(PreferredShopsEditor.new);

/// Switches which shop the app is acting for, and throws away what belonged
/// to the last one.
///
/// WHY THE INVALIDATION IS THE POINT. ShopContext on its own only changes a
/// header. Every cached list in the app - the categories, the feed, the
/// offers, the store's opening hours - was fetched under the previous shop
/// and is now somebody else's shop's data sitting on screen under a new
/// name. Riverpod will not know that; the shop is not in those providers'
/// keys. So the switch and the discard happen together, here, and callers
/// have one function to call rather than a list to remember.
///
/// IT GRANTS NOTHING. The header this eventually produces can only NARROW a
/// scope the credential already permits - see ShopContext and, on the server,
/// TenantResolver.select, which refuses a shop the caller is not entitled to
/// rather than honouring it. Selecting a shop is a preference; whether it is
/// allowed is re-decided on every request.
class ShopSwitch {
  const ShopSwitch(this._ref);

  final Ref _ref;

  void select(int shopId) {
    if (_ref.read(shopContextProvider) == shopId) return;
    _ref.read(shopContextProvider.notifier).select(shopId);
    _discardTheLastShopsData();
  }

  void clear() {
    if (_ref.read(shopContextProvider) == null) return;
    _ref.read(shopContextProvider.notifier).clear();
    _discardTheLastShopsData();
  }

  /// Everything whose answer depends on WHICH SHOP asked.
  ///
  /// A new provider that reads shop-scoped data belongs in this list. The
  /// test for membership is one question: would this have come back
  /// different if a different shop's id had been on the request?
  void _discardTheLastShopsData() {
    // The catalogue, which is per-shop priced and per-shop listed.
    _ref.invalidate(categoriesProvider);
    _ref.invalidate(brandsProvider);
    _ref.invalidate(activeOffersProvider);
    _ref.invalidate(newArrivalsProvider);
    _ref.invalidate(bestsellerTilesProvider);
    _ref.invalidate(trendingProvider);
    _ref.invalidate(recommendedForMeProvider);
    _ref.invalidate(categoryPreviewProvider);
    _ref.invalidate(productDetailProvider);
    _ref.invalidate(productFeedProvider);
    // When this shop opens, and whether it is taking orders at all.
    _ref.invalidate(storeStatusProvider);
    // The basket: its lines keep the shop they were added from, and the
    // server re-prices against the shop on the request.
    _ref.invalidate(cartControllerProvider);
    // Order history is read under the request's scope.
    _ref.invalidate(myOrdersProvider);
    // The per-shop basket: its delivery quotes were worked out against the
    // shops that were in the basket, and switching can add one.
    _ref.invalidate(basketByShopProvider);
  }
}

final shopSwitchProvider = Provider<ShopSwitch>(ShopSwitch.new);
