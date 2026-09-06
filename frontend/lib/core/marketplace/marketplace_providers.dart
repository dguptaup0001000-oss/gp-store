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
final selectedStorefrontProvider =
    FutureProvider.autoDispose<Storefront?>((ref) async {
  final shopId = ref.watch(shopContextProvider);
  if (shopId == null) return null;
  return ref.watch(marketplaceRepositoryProvider).storefront(shopId);
});

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
  }
}

final shopSwitchProvider = Provider<ShopSwitch>(ShopSwitch.new);
