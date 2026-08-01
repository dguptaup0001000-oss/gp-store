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

final adminAllOrdersProvider = FutureProvider<List<OrderSummary>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllOrders();
});

final adminCustomerOrdersProvider = FutureProvider.family<List<OrderSummary>, int>((ref, customerId) {
  return ref.watch(adminProductsRepositoryProvider).getCustomerOrders(customerId);
});

final adminAllReviewsProvider = FutureProvider<List<AdminReview>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllReviews();
});

final adminAllCustomersProvider = FutureProvider<List<AdminCustomer>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllCustomers();
});

final adminAllPaymentsProvider = FutureProvider<List<AdminPayment>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAllPayments();
});

final adminAvailablePartnersProvider = FutureProvider<List<DeliveryPartnerModel>>((ref) {
  return ref.watch(adminProductsRepositoryProvider).getAvailablePartners();
});
