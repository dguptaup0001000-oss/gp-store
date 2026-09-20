import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/marketplace/shop_context.dart';
import '../../features/admin/presentation/admin_providers.dart';
import '../../features/admin/presentation/shop_self_service_providers.dart';
import '../operations/store_operations_providers.dart';

/// Switching shop, and throwing away everything that belonged to the last one.
///
/// WHY THIS IS A LIST AND NOT A CASCADE. The obvious trick - make the API
/// client depend on the selected shop, so every repository rebuilds for free -
/// is deliberately not available: ApiClient reads the shop with `ref.read` per
/// request, because rebuilding the client would tear down its interceptor
/// chain and its in-flight refresh guard and drop requests already in the air.
/// That reasoning is sound and is not worth undoing for a convenience here.
///
/// So the state that has to go is named. The risk with a named list is that
/// somebody adds a provider next month and does not add it here, and a
/// merchant sees Deepak Hardware's stock under Deepak Saree. That is what
/// ShopSwitchClearsEverythingTest is for: it reads the provider files and
/// fails if a shop-scoped provider is not accounted for, which turns a thing
/// people forget into a thing the build refuses.
///
/// INVALIDATE, NOT REFRESH. Invalidating drops the value and lets whatever is
/// on screen ask again; refreshing would fetch immediately for shops nobody is
/// looking at, which on a counter-top phone on a village connection is a
/// handful of pointless requests at the exact moment the merchant is waiting.
///
/// §36 says an early implementation may invalidate more broadly than strictly
/// necessary, and this does: the merchant profile and the shop list go too,
/// even though the first is merchant-level and the second is about to be
/// re-fetched anyway. Broad and correct beats narrow and subtly wrong.
void switchToShop(WidgetRef ref, int shopId) {
  final current = ref.read(shopContextProvider);
  if (current == shopId) {
    return;
  }
  ref.read(shopContextProvider.notifier).select(shopId);
  clearEverythingShopScoped(ref);
}

/// Drops every cached answer that was about the shop we just left.
///
/// Public and separate from [switchToShop] so a test can call it, and so
/// signing out can use the same list rather than a second copy of it.
void clearEverythingShopScoped(WidgetRef ref) {
  for (final provider in shopScopedProviders) {
    ref.invalidate(provider);
  }
}

/// Everything whose answer is about one shop.
///
/// Kept as data rather than a hand-written sequence of invalidate calls so the
/// guard test can count it.
final List<ProviderOrFamily> shopScopedProviders = <ProviderOrFamily>[
  // The shop itself
  myShopProfileProvider,
  myShopReadinessProvider,
  myShopEarningsProvider,
  myShopOpenWorkProvider,
  myShopsProvider,
  myMerchantProfileProvider,

  // Orders, packing and money
  adminAllOrdersProvider,
  adminAllPaymentsProvider,
  adminOrderStatusBreakdownProvider,
  adminOrderDeliveryBreakdownProvider,
  adminDeliveryBreachesProvider,
  preparationListProvider,

  // Catalogue and stock
  adminAllProductsProvider,
  adminCategoriesProvider,
  // The shop's own departments are shop-scoped data: without this line,
  // switching shops keeps showing the previous shop's Categories screen.
  adminMyCategoriesProvider,
  // The departments the merchant created themselves. Same reasoning, and the
  // one the guard test caught: a phone shop's "charger" must not still be on
  // screen after switching to the saree shop.
  adminShopCategoriesProvider,
  adminAllInventoryProvider,
  adminLowStockProvider,
  adminLowStockCountProvider,
  adminAllCouponsProvider,
  catalogImportHistoryProvider,
  catalogImportProblemsProvider,

  // People
  adminAllCustomersProvider,
  adminCustomerDetailProvider,
  adminCustomerOrdersProvider,
  adminWorkersProvider,
  adminDeliveryPartnersProvider,
  adminAvailablePartnersProvider,
  adminPresenceProvider,

  // Reviews and reporting
  adminAllReviewsProvider,
  adminAuditLogProvider,
  adminSalesSummaryProvider,
  // One shop's interest in its own listings. A merchant switching to their
  // second shop must not see the first shop's directions and calls.
  adminListingEngagementProvider,
  adminSalesSeriesProvider,
  adminTopProductsProvider,
  deliveryTypeSharesProvider,

  // Settings, hours and delivery configuration
  storeOperationsProvider,
  deliveryPricingSettingsProvider,
  territoryZonesProvider,
  territorySubzonesProvider,
  territoryHealthProvider,
];
