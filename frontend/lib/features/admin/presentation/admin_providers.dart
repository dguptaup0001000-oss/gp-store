import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../domain/presence_model.dart';

import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart';
import '../../orders/domain/order_models.dart';
import '../data/admin_products_repository.dart';
import '../data/delivery_pricing_repository.dart';
import '../data/territory_repository.dart';
import '../domain/admin_coupon_models.dart';
import '../domain/admin_customer_model.dart';
import '../domain/admin_payment_model.dart';
import '../domain/admin_review_model.dart';
import '../domain/analytics_models.dart';
import '../domain/audit_log_model.dart';
import '../domain/delivery_breach_model.dart';
import '../domain/delivery_partner_models.dart';
import '../domain/delivery_pricing_models.dart';
import '../domain/inventory_models.dart';
import '../domain/territory_models.dart';

final adminProductsRepositoryProvider = Provider<AdminProductsRepository>((ref) {
  return AdminProductsRepository(apiClient: ref.watch(apiClientProvider));
});

// autoDispose on every provider below this point (admin screens are
// drill-down: visited occasionally, not the app's persistent home tab) -
// a store owner clicking through Products, Inventory, Coupons, Analytics,
// Audit Log, etc. over one session would otherwise leave every single one
// of those lists cached in memory for the rest of the app's lifetime,
// growing unboundedly the longer an admin session runs.
final adminAllProductsProvider = FutureProvider.autoDispose<List<Product>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllForAdmin();
});

final adminCategoriesProvider = FutureProvider.autoDispose<List<Category>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getCategories();
});

typedef AdminInventoryPage = ({List<InventoryItem> items, int page, int totalPages});

/// Paginated - see AdminAllOrdersController's doc comment for why this is an
/// AsyncNotifier rather than a plain FutureProvider.
class AdminAllInventoryController extends AutoDisposeAsyncNotifier<AdminInventoryPage> {
  @override
  Future<AdminInventoryPage> build() async {
    final result = await ref.read(adminProductsRepositoryProvider).getAllInventory(page: 0);
    return (items: result.items, page: 0, totalPages: result.totalPages);
  }

  bool get hasMore {
    final current = state.valueOrNull;
    return current != null && current.page + 1 < current.totalPages;
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.page + 1 >= current.totalPages) return;

    final nextPage = current.page + 1;
    final result = await ref.read(adminProductsRepositoryProvider).getAllInventory(page: nextPage);
    state = AsyncData((
      items: [...current.items, ...result.items],
      page: nextPage,
      totalPages: result.totalPages,
    ));
  }
}

final adminAllInventoryProvider =
    AsyncNotifierProvider.autoDispose<AdminAllInventoryController, AdminInventoryPage>(
  AdminAllInventoryController.new,
);

final adminLowStockProvider = FutureProvider.autoDispose<List<InventoryItem>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getLowStock();
});

final adminAllCouponsProvider = FutureProvider.autoDispose<List<AdminCoupon>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllCoupons();
});

final adminDeliveryPartnersProvider = FutureProvider.autoDispose<List<DeliveryPartnerModel>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllDeliveryPartners();
});

/// Lets the dashboard's period selector (7/30/90 days) drive both the sales
/// summary and top products together, without duplicating the days value.
final analyticsPeriodDaysProvider = StateProvider.autoDispose<int>((ref) => 30);

final adminSalesSummaryProvider = FutureProvider.autoDispose<SalesSummary>((ref) {
  final days = ref.watch(analyticsPeriodDaysProvider);
  return ref.watch(adminProductsRepositoryProvider).getSalesSummary(days: days);
});

/// The dashboard chart. Shares analyticsPeriodDaysProvider with the summary
/// and the leaderboard, so the period selector moves all three at once and
/// they can never disagree about which window is on screen.
final adminSalesSeriesProvider = FutureProvider.autoDispose<List<SalesPoint>>((ref) {
  final days = ref.watch(analyticsPeriodDaysProvider);
  return ref.watch(adminProductsRepositoryProvider).getSalesSeries(days: days);
});

final adminOrderStatusBreakdownProvider = FutureProvider.autoDispose<Map<String, int>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getOrderStatusBreakdown();
});

final adminTopProductsProvider = FutureProvider.autoDispose<List<TopProduct>>((ref) {
  final days = ref.watch(analyticsPeriodDaysProvider);
  return ref.watch(adminProductsRepositoryProvider).getTopProducts(days: days);
});

/// Who is in the shop right now.
///
/// autoDispose so it stops being fetched the moment the dashboard is closed -
/// this is the one provider here that a screen refreshes on a timer, and a
/// kept-alive version would keep polling in the background for as long as the
/// admin app stayed open.
final adminPresenceProvider = FutureProvider.autoDispose<PresenceSnapshot>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getPresence();
});

final adminLowStockCountProvider = FutureProvider.autoDispose<int>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getLowStockCount();
});

final adminDeliveryBreachesProvider = FutureProvider.autoDispose<List<DeliveryBreach>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getBreachedDeliveries();
});

final adminAuditLogProvider = FutureProvider.autoDispose<List<AuditLogEntry>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAuditLog();
});

typedef AdminOrdersPage = ({List<OrderSummary> orders, int page, int totalPages});

/// Paginated - every order ever placed, system-wide, has no natural upper
/// bound. AsyncNotifier (not a plain FutureProvider) so loadMore() can
/// append to the existing state.
class AdminAllOrdersController extends AutoDisposeAsyncNotifier<AdminOrdersPage> {
  @override
  Future<AdminOrdersPage> build() async {
    final result = await ref.read(adminProductsRepositoryProvider).getAllOrders(page: 0);
    return (orders: result.orders, page: 0, totalPages: result.totalPages);
  }

  bool get hasMore {
    final current = state.valueOrNull;
    return current != null && current.page + 1 < current.totalPages;
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.page + 1 >= current.totalPages) return;

    final nextPage = current.page + 1;
    final result = await ref.read(adminProductsRepositoryProvider).getAllOrders(page: nextPage);
    state = AsyncData((
      orders: [...current.orders, ...result.orders],
      page: nextPage,
      totalPages: result.totalPages,
    ));
  }
}

final adminAllOrdersProvider = AsyncNotifierProvider.autoDispose<AdminAllOrdersController, AdminOrdersPage>(
  AdminAllOrdersController.new,
);

final adminCustomerOrdersProvider = FutureProvider.autoDispose.family<List<OrderSummary>, int>((ref, customerId) {
  return ref.watch(adminProductsRepositoryProvider).getCustomerOrders(customerId);
});

typedef AdminReviewsPage = ({List<AdminReview> reviews, int page, int totalPages});

/// Paginated - see AdminAllOrdersController's doc comment for why this is an
/// AsyncNotifier rather than a plain FutureProvider.
class AdminAllReviewsController extends AutoDisposeAsyncNotifier<AdminReviewsPage> {
  @override
  Future<AdminReviewsPage> build() async {
    final result = await ref.read(adminProductsRepositoryProvider).getAllReviews(page: 0);
    return (reviews: result.reviews, page: 0, totalPages: result.totalPages);
  }

  bool get hasMore {
    final current = state.valueOrNull;
    return current != null && current.page + 1 < current.totalPages;
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.page + 1 >= current.totalPages) return;

    final nextPage = current.page + 1;
    final result = await ref.read(adminProductsRepositoryProvider).getAllReviews(page: nextPage);
    state = AsyncData((
      reviews: [...current.reviews, ...result.reviews],
      page: nextPage,
      totalPages: result.totalPages,
    ));
  }
}

final adminAllReviewsProvider = AsyncNotifierProvider.autoDispose<AdminAllReviewsController, AdminReviewsPage>(
  AdminAllReviewsController.new,
);

typedef AdminCustomersPage = ({List<AdminCustomer> customers, int page, int totalPages});

/// Paginated - see AdminAllOrdersController's doc comment for why this is an
/// AsyncNotifier rather than a plain FutureProvider.
class AdminAllCustomersController extends AutoDisposeAsyncNotifier<AdminCustomersPage> {
  @override
  Future<AdminCustomersPage> build() async {
    final result = await ref.read(adminProductsRepositoryProvider).getAllCustomers(page: 0);
    return (customers: result.customers, page: 0, totalPages: result.totalPages);
  }

  bool get hasMore {
    final current = state.valueOrNull;
    return current != null && current.page + 1 < current.totalPages;
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.page + 1 >= current.totalPages) return;

    final nextPage = current.page + 1;
    final result = await ref.read(adminProductsRepositoryProvider).getAllCustomers(page: nextPage);
    state = AsyncData((
      customers: [...current.customers, ...result.customers],
      page: nextPage,
      totalPages: result.totalPages,
    ));
  }
}

final adminAllCustomersProvider = AsyncNotifierProvider.autoDispose<AdminAllCustomersController, AdminCustomersPage>(
  AdminAllCustomersController.new,
);

typedef AdminPaymentsPage = ({List<AdminPayment> payments, int page, int totalPages});

/// Paginated - see AdminAllOrdersController's doc comment for why this is an
/// AsyncNotifier rather than a plain FutureProvider.
class AdminAllPaymentsController extends AutoDisposeAsyncNotifier<AdminPaymentsPage> {
  @override
  Future<AdminPaymentsPage> build() async {
    final result = await ref.read(adminProductsRepositoryProvider).getAllPayments(page: 0);
    return (payments: result.payments, page: 0, totalPages: result.totalPages);
  }

  bool get hasMore {
    final current = state.valueOrNull;
    return current != null && current.page + 1 < current.totalPages;
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.page + 1 >= current.totalPages) return;

    final nextPage = current.page + 1;
    final result = await ref.read(adminProductsRepositoryProvider).getAllPayments(page: nextPage);
    state = AsyncData((
      payments: [...current.payments, ...result.payments],
      page: nextPage,
      totalPages: result.totalPages,
    ));
  }
}

final adminAllPaymentsProvider = AsyncNotifierProvider.autoDispose<AdminAllPaymentsController, AdminPaymentsPage>(
  AdminAllPaymentsController.new,
);

final adminAvailablePartnersProvider = FutureProvider.autoDispose<List<DeliveryPartnerModel>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAvailablePartners();
});

final deliveryPricingRepositoryProvider = Provider<DeliveryPricingRepository>((ref) {
  return DeliveryPricingRepository(apiClient: ref.watch(apiClientProvider));
});

final deliveryPricingSettingsProvider = FutureProvider.autoDispose<DeliveryPricingSettings>((ref) {
  return ref.watch(deliveryPricingRepositoryProvider).getSettings();
});

final adminOrderDeliveryBreakdownProvider =
    FutureProvider.autoDispose.family<DeliveryOrderBreakdown, int>((ref, orderId) {
  return ref.watch(deliveryPricingRepositoryProvider).getOrderBreakdown(orderId);
});

final territoryRepositoryProvider = Provider<TerritoryRepository>((ref) {
  return TerritoryRepository(apiClient: ref.watch(apiClientProvider));
});

final territoryHealthProvider = FutureProvider.autoDispose<TerritoryHealth>((ref) {
  return ref.watch(territoryRepositoryProvider).getHealth();
});

final territoryZonesProvider = FutureProvider.autoDispose<List<TerritoryZone>>((ref) {
  return ref.watch(territoryRepositoryProvider).listZones();
});

final territorySubzonesProvider = FutureProvider.autoDispose<List<TerritorySubzone>>((ref) {
  return ref.watch(territoryRepositoryProvider).listSubzones();
});
