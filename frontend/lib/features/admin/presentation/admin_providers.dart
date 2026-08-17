import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart';
import '../data/admin_products_repository.dart';
import '../domain/admin_coupon_models.dart';
import '../domain/admin_customer_model.dart';
import '../domain/admin_payment_model.dart';
import '../domain/admin_review_model.dart';
import '../domain/analytics_models.dart';
import '../domain/audit_log_model.dart';
import '../domain/delivery_breach_model.dart';
import '../domain/delivery_partner_models.dart';
import '../../orders/domain/order_models.dart';
import '../domain/inventory_models.dart';

final adminProductsRepositoryProvider = Provider<AdminProductsRepository>((ref) {
  return AdminProductsRepository(apiClient: ref.watch(apiClientProvider));
});

final adminAllProductsProvider = FutureProvider<List<Product>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllForAdmin();
});

final adminCategoriesProvider = FutureProvider<List<Category>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getCategories();
});

final adminAllInventoryProvider = FutureProvider<List<InventoryItem>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllInventory();
});

final adminLowStockProvider = FutureProvider<List<InventoryItem>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getLowStock();
});

final adminAllCouponsProvider = FutureProvider<List<AdminCoupon>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllCoupons();
});

final adminDeliveryPartnersProvider = FutureProvider<List<DeliveryPartnerModel>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllDeliveryPartners();
});

/// Lets the dashboard's period selector (7/30/90 days) drive both the sales
/// summary and top products together, without duplicating the days value.
final analyticsPeriodDaysProvider = StateProvider<int>((ref) => 30);

final adminSalesSummaryProvider = FutureProvider<SalesSummary>((ref) {
  final days = ref.watch(analyticsPeriodDaysProvider);
  return ref.watch(adminProductsRepositoryProvider).getSalesSummary(days: days);
});

final adminOrderStatusBreakdownProvider = FutureProvider<Map<String, int>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getOrderStatusBreakdown();
});

final adminTopProductsProvider = FutureProvider<List<TopProduct>>((ref) {
  final days = ref.watch(analyticsPeriodDaysProvider);
  return ref.watch(adminProductsRepositoryProvider).getTopProducts(days: days);
});

final adminLowStockCountProvider = FutureProvider<int>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getLowStockCount();
});

final adminDeliveryBreachesProvider = FutureProvider<List<DeliveryBreach>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getBreachedDeliveries();
});

final adminAuditLogProvider = FutureProvider<List<AuditLogEntry>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAuditLog();
});

typedef AdminOrdersPage = ({List<OrderSummary> orders, int page, int totalPages});

/// Paginated - every order ever placed, system-wide, has no natural upper
/// bound. AsyncNotifier (not a plain FutureProvider) so loadMore() can
/// append to the existing state.
class AdminAllOrdersController extends AsyncNotifier<AdminOrdersPage> {
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

final adminAllOrdersProvider = AsyncNotifierProvider<AdminAllOrdersController, AdminOrdersPage>(
  AdminAllOrdersController.new,
);

final adminCustomerOrdersProvider = FutureProvider.family<List<OrderSummary>, int>((ref, customerId) {
  return ref.watch(adminProductsRepositoryProvider).getCustomerOrders(customerId);
});

typedef AdminReviewsPage = ({List<AdminReview> reviews, int page, int totalPages});

/// Paginated - see AdminAllOrdersController's doc comment for why this is an
/// AsyncNotifier rather than a plain FutureProvider.
class AdminAllReviewsController extends AsyncNotifier<AdminReviewsPage> {
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

final adminAllReviewsProvider = AsyncNotifierProvider<AdminAllReviewsController, AdminReviewsPage>(
  AdminAllReviewsController.new,
);

typedef AdminCustomersPage = ({List<AdminCustomer> customers, int page, int totalPages});

/// Paginated - see AdminAllOrdersController's doc comment for why this is an
/// AsyncNotifier rather than a plain FutureProvider.
class AdminAllCustomersController extends AsyncNotifier<AdminCustomersPage> {
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

final adminAllCustomersProvider = AsyncNotifierProvider<AdminAllCustomersController, AdminCustomersPage>(
  AdminAllCustomersController.new,
);

typedef AdminPaymentsPage = ({List<AdminPayment> payments, int page, int totalPages});

/// Paginated - see AdminAllOrdersController's doc comment for why this is an
/// AsyncNotifier rather than a plain FutureProvider.
class AdminAllPaymentsController extends AsyncNotifier<AdminPaymentsPage> {
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

final adminAllPaymentsProvider = AsyncNotifierProvider<AdminAllPaymentsController, AdminPaymentsPage>(
  AdminAllPaymentsController.new,
);

final adminAvailablePartnersProvider = FutureProvider<List<DeliveryPartnerModel>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAvailablePartners();
});
