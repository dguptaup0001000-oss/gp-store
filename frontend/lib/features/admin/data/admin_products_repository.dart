import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/api/api_client.dart';
import '../../products/domain/product_models.dart';
import '../domain/admin_coupon_models.dart';
import '../domain/admin_customer_model.dart';
import '../domain/admin_payment_model.dart';
import '../domain/admin_review_model.dart';
import '../domain/analytics_models.dart';
import '../domain/audit_log_model.dart';
import '../domain/cloudinary_signature.dart';
import '../domain/delivery_breach_model.dart';
import '../domain/delivery_partner_models.dart';
import '../../orders/domain/order_models.dart';
import '../domain/inventory_models.dart';

class AdminProductsRepository {
  AdminProductsRepository({required this.apiClient});

  final ApiClient apiClient;

  /// Includes deactivated products too, unlike the customer-facing list.
  Future<List<Product>> getAllForAdmin() async {
    final response = await apiClient.dio.get('/api/products/admin/all');
    return (response.data as List).map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<Product> createProduct({
    required String name,
    String? brand,
    required int categoryId,
  }) async {
    final response = await apiClient.dio.post('/api/products', data: {
      'name': name,
      'brand': brand,
      'category': {'id': categoryId},
      'active': true,
    });
    return Product.fromJson(response.data as Map<String, dynamic>);
  }

  Future<Product> updateProduct({
    required int productId,
    required String name,
    String? brand,
    required int categoryId,
    required bool active,
  }) async {
    final response = await apiClient.dio.put('/api/products/$productId', data: {
      'name': name,
      'brand': brand,
      'category': {'id': categoryId},
      'active': active,
    });
    return Product.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> deactivateProduct(int productId) async {
    await apiClient.dio.delete('/api/products/$productId');
  }

  /// costPrice/sku/barcode/gstRateOverride are write-only fields the
  /// customer-facing ProductVariant model never returns (see
  /// ProductVariant.costPrice's @JsonProperty WRITE_ONLY on the backend) -
  /// this is why variant creation takes plain named parameters here instead
  /// of reusing that read model.
  Future<void> createVariant({
    required int productId,
    required double quantity,
    required String unit,
    String? imageUrl,
    required double mrp,
    required double sellingPrice,
    double? costPrice,
    bool allowBelowCost = false,
  }) async {
    await apiClient.dio.post(
      '/api/product-variants',
      queryParameters: {'allowBelowCost': allowBelowCost},
      data: {
        'product': {'id': productId},
        'quantity': quantity,
        'unit': unit,
        'imageUrl': imageUrl,
        'mrp': mrp,
        'sellingPrice': sellingPrice,
        'costPrice': costPrice,
        'available': true,
        'active': true,
      },
    );
  }

  Future<void> updateVariant({
    required int variantId,
    required double quantity,
    required String unit,
    String? imageUrl,
    required double mrp,
    required double sellingPrice,
    double? costPrice,
    required bool available,
    bool allowBelowCost = false,
  }) async {
    await apiClient.dio.put(
      '/api/product-variants/$variantId',
      queryParameters: {'allowBelowCost': allowBelowCost},
      data: {
        'quantity': quantity,
        'unit': unit,
        'imageUrl': imageUrl,
        'mrp': mrp,
        'sellingPrice': sellingPrice,
        'costPrice': costPrice,
        'available': available,
        'active': true,
      },
    );
  }

  /// Short-lived signature for one direct-to-Cloudinary upload - the API
  /// secret used to produce it never leaves the backend (see
  /// CloudinaryUploadService's doc comment). Throws (surfacing the
  /// backend's own message via extractErrorMessage) if Cloudinary hasn't
  /// been configured yet.
  Future<CloudinarySignature> getCloudinarySignature() async {
    final response = await apiClient.dio.get('/api/uploads/cloudinary-signature');
    return CloudinarySignature.fromJson(response.data as Map<String, dynamic>);
  }

  /// Lets the admin pick a photo from their gallery and upload it straight
  /// to Cloudinary for a variant's image - this app's own backend never
  /// receives the image bytes, only the short-lived signature above. Uses a
  /// fresh, unconfigured Dio instance (not apiClient.dio) so this app's JWT
  /// auth header and error-mapping interceptor - both meant for OUR
  /// backend's responses - never touch this third-party request. Returns
  /// null if the admin cancelled the picker instead of choosing a photo.
  Future<String?> pickAndUploadVariantImage() async {
    final picked = await ImagePicker().pickImage(source: ImageSource.gallery, imageQuality: 85);
    if (picked == null) return null;

    final signature = await getCloudinarySignature();
    final bytes = await picked.readAsBytes();

    final response = await Dio().post(
      'https://api.cloudinary.com/v1_1/${signature.cloudName}/image/upload',
      data: FormData.fromMap({
        'file': MultipartFile.fromBytes(bytes, filename: picked.name),
        'api_key': signature.apiKey,
        'timestamp': signature.timestamp,
        'signature': signature.signature,
        'folder': signature.folder,
      }),
    );

    return response.data['secure_url'] as String;
  }

  Future<List<Category>> getCategories() async {
    final response = await apiClient.dio.get('/api/categories');
    return (response.data as List).map((e) => Category.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> createCategory({required String name, String? description, double? gstRate}) async {
    await apiClient.dio.post('/api/categories', data: {
      'name': name,
      'description': description,
      'gstRate': gstRate,
      'active': true,
    });
  }

  /// Backend's CategoryService.update() copies every field from the
  /// request body - same reasoning as address/delivery-partner updates,
  /// always send a complete picture, never a partial one.
  Future<void> updateCategory({
    required int categoryId,
    required String name,
    String? description,
    double? gstRate,
    required bool active,
  }) async {
    await apiClient.dio.put('/api/categories/$categoryId', data: {
      'name': name,
      'description': description,
      'gstRate': gstRate,
      'active': active,
    });
  }

  /// Soft delete only - safe even if products still reference this
  /// category (they keep their reference; it just stops appearing to
  /// customers). Matches the backend's own reasoning exactly.
  Future<void> deactivateCategory(int categoryId) async {
    await apiClient.dio.delete('/api/categories/$categoryId');
  }

  // Real pagination now (used to be a bare unbounded array) - every
  // inventory row ever created was being loaded on every visit to this
  // screen otherwise.
  Future<({List<InventoryItem> items, int totalPages})> getAllInventory({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/inventory',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      items: content.map((e) => InventoryItem.fromJson(e as Map<String, dynamic>)).toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  Future<List<InventoryItem>> getLowStock() async {
    final response = await apiClient.dio.get('/api/inventory/low-stock');
    return (response.data as List).map((e) => InventoryItem.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Additive - "we received N more units", not a replacement value. Matches
  /// the backend's restock endpoint exactly.
  Future<void> restock({required int inventoryId, required int quantity}) async {
    await apiClient.dio.put(
      '/api/inventory/$inventoryId/restock',
      queryParameters: {'quantity': quantity},
    );
  }

  /// Full manual correction (e.g. after a physical stock-take) - sets exact
  /// values, unlike restock() above which only adds.
  ///
  /// currentReservedStock MUST be the InventoryItem's existing value, passed
  /// straight through unchanged - the backend's update() copies whatever
  /// reservedStock is in this request body with no fallback, so omitting it
  /// would silently wipe real stock already committed to in-progress orders,
  /// not just leave it as-is.
  Future<void> updateInventory({
    required int inventoryId,
    required int stock,
    required int minimumStock,
    int? maximumStock,
    required int? currentReservedStock,
  }) async {
    await apiClient.dio.put('/api/inventory/$inventoryId', data: {
      'stock': stock,
      'reservedStock': currentReservedStock,
      'minimumStock': minimumStock,
      'maximumStock': maximumStock,
    });
  }

  // --- Coupons ---

  Future<List<AdminCoupon>> getAllCoupons() async {
    final response = await apiClient.dio.get('/api/coupons');
    return (response.data as List).map((e) => AdminCoupon.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> createCoupon({
    required String couponCode,
    required DiscountType discountType,
    required double discountValue,
    double? maxDiscountAmount,
    double? minimumOrderAmount,
    String? expiryDate,
    int? usageLimit,
  }) async {
    await apiClient.dio.post('/api/coupons', data: {
      'couponCode': couponCode.toUpperCase(),
      'discountType': discountType == DiscountType.flat ? 'FLAT' : 'PERCENTAGE',
      'discountValue': discountValue,
      'maxDiscountAmount': maxDiscountAmount,
      'minimumOrderAmount': minimumOrderAmount,
      'expiryDate': expiryDate,
      'usageLimit': usageLimit,
      'active': true,
    });
  }

  /// The backend's update() intentionally ignores couponCode/usedCount even
  /// if sent (see CouponService.update's doc comment) - they're included
  /// here anyway just so this stays a complete, self-consistent object.
  Future<void> updateCoupon({
    required int couponId,
    required DiscountType discountType,
    required double discountValue,
    double? maxDiscountAmount,
    double? minimumOrderAmount,
    String? expiryDate,
    int? usageLimit,
    required bool active,
  }) async {
    await apiClient.dio.put('/api/coupons/$couponId', data: {
      'discountType': discountType == DiscountType.flat ? 'FLAT' : 'PERCENTAGE',
      'discountValue': discountValue,
      'maxDiscountAmount': maxDiscountAmount,
      'minimumOrderAmount': minimumOrderAmount,
      'expiryDate': expiryDate,
      'usageLimit': usageLimit,
      'active': active,
    });
  }

  Future<void> deactivateCoupon(int couponId) async {
    await apiClient.dio.delete('/api/coupons/$couponId');
  }

  // --- Delivery partners ---

  Future<List<DeliveryPartnerModel>> getAllDeliveryPartners() async {
    final response = await apiClient.dio.get('/api/delivery-partners');
    return (response.data as List).map((e) => DeliveryPartnerModel.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> createDeliveryPartner(DeliveryPartnerModel partner) async {
    await apiClient.dio.post('/api/delivery-partners', data: partner.toJson());
  }

  /// Backend's update() does a full save() of whatever's in the body, with
  /// no partial-update handling - the id MUST be included and every field
  /// MUST be sent together, or missing fields get silently nulled out
  /// (same class of risk as inventory's reservedStock - see that repository
  /// method's doc comment). Always call this with a complete
  /// DeliveryPartnerModel, never a partial one.
  Future<void> updateDeliveryPartner(DeliveryPartnerModel partner) async {
    assert(partner.id != null, 'Cannot update a delivery partner without an id');
    await apiClient.dio.put('/api/delivery-partners', data: partner.toJson());
  }

  // --- Analytics ---

  Future<SalesSummary> getSalesSummary({int days = 30}) async {
    final response = await apiClient.dio.get('/api/analytics/sales-summary', queryParameters: {'days': days});
    return SalesSummary.fromJson(response.data as Map<String, dynamic>);
  }

  /// Map values come back as JSON numbers (Long serializes as a plain
  /// number, not a string) - cast explicitly rather than assume int.
  Future<Map<String, int>> getOrderStatusBreakdown() async {
    final response = await apiClient.dio.get('/api/analytics/order-status-breakdown');
    return (response.data as Map<String, dynamic>).map((key, value) => MapEntry(key, (value as num).toInt()));
  }

  Future<List<TopProduct>> getTopProducts({int days = 30, int limit = 10}) async {
    final response = await apiClient.dio.get(
      '/api/analytics/top-products',
      queryParameters: {'days': days, 'limit': limit},
    );
    return (response.data as List).map((e) => TopProduct.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<int> getLowStockCount() async {
    final response = await apiClient.dio.get('/api/analytics/low-stock-count');
    return (response.data['lowStockCount'] as num).toInt();
  }

  // --- Delivery guarantee breaches ---

  /// The manual-review list for the delivery guarantee - no auto-refund is
  /// attached to this by design (see backend's own comment on this
  /// endpoint) - purely a signal for the admin to act on.
  Future<List<DeliveryBreach>> getBreachedDeliveries() async {
    final response = await apiClient.dio.get('/api/deliveries/breached');
    return (response.data as List).map((e) => DeliveryBreach.fromJson(e as Map<String, dynamic>)).toList();
  }

  // --- Audit log ---

  Future<List<AuditLogEntry>> getAuditLog({int page = 0, int size = 50}) async {
    final response = await apiClient.dio.get(
      '/api/audit-logs',
      queryParameters: {'page': page, 'size': size},
    );
    final content = response.data['content'] as List;
    return content.map((e) => AuditLogEntry.fromJson(e as Map<String, dynamic>)).toList();
  }

  // --- Orders ---

  /// Every order in the system, WITH customer name (a raw entity dump
  /// alone can't show this - Order.customer is hidden from JSON). Order
  /// detail viewing reuses the existing customer-facing orderDetailProvider
  /// directly - the backend now allows admin to bypass the ownership check
  /// on that same endpoint, so no separate admin detail call is needed.
  /// Paginated - every order ever placed, system-wide, has no natural upper bound.
  Future<({List<OrderSummary> orders, int totalPages})> getAllOrders({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/orders/admin/all',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      orders: content.map((e) => OrderSummary.fromJson(e as Map<String, dynamic>)).toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  /// A specific customer's order history - for support/dispute lookups
  /// ("this customer says their order never arrived").
  Future<List<OrderSummary>> getCustomerOrders(int customerId) async {
    final response = await apiClient.dio.get('/api/orders/customer/$customerId');
    return (response.data as List).map((e) => OrderSummary.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> updateOrderStatus({required int orderId, required String status}) async {
    await apiClient.dio.put(
      '/api/orders/$orderId/status',
      queryParameters: {'status': status},
    );
  }

  // --- Review moderation ---

  /// Paginated - system-wide review count has no natural upper bound.
  Future<({List<AdminReview> reviews, int totalPages})> getAllReviews({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/reviews',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      reviews: content.map((e) => AdminReview.fromJson(e as Map<String, dynamic>)).toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  /// Moderation delete - removes ANY review, not just the admin's own.
  Future<void> moderateDeleteReview(int reviewId) async {
    await apiClient.dio.delete('/api/reviews/$reviewId/moderate');
  }

  // --- Customer management ---

  /// Paginated - the customer base has no natural upper bound.
  Future<({List<AdminCustomer> customers, int totalPages})> getAllCustomers({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/customers',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      customers: content.map((e) => AdminCustomer.fromJson(e as Map<String, dynamic>)).toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  /// password is genuinely optional - e.g. a phone-order customer who'll
  /// log in via OTP later. The backend now handles a null password
  /// correctly for this exact case (previously it would throw).
  Future<void> createCustomer({
    required String fullName,
    String? email,
    required String mobileNumber,
    String? password,
  }) async {
    await apiClient.dio.post('/api/customers', data: {
      'fullName': fullName,
      'email': email,
      'mobileNumber': mobileNumber,
      'password': password,
    });
  }

  /// Deactivating also force-logs-out every device that customer is signed
  /// into - see the backend's CustomerService.setAccountActive doc comment.
  Future<void> setCustomerActive({required int customerId, required bool active}) async {
    await apiClient.dio.put(
      '/api/customers/$customerId/active',
      queryParameters: {'active': active},
    );
  }

  // --- Payments ---

  /// Paginated - system-wide payment count has no natural upper bound.
  Future<({List<AdminPayment> payments, int totalPages})> getAllPayments({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/payments',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      payments: content.map((e) => AdminPayment.fromJson(e as Map<String, dynamic>)).toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  /// Starts a refund for a cancelled/returned order's payment.
  Future<void> refundPayment(int orderId) async {
    await apiClient.dio.put('/api/payments/order/$orderId/refund');
  }

  /// Marks a previously-started refund as actually completed (money sent).
  Future<void> completeRefund(int orderId) async {
    await apiClient.dio.put('/api/payments/order/$orderId/refund/complete');
  }

  /// Confirms a direct (fee-free) UPI payment actually arrived - there's no
  /// gateway webhook doing this automatically, so admin confirms manually.
  Future<void> confirmUpiPayment(int orderId, {String? transactionId}) async {
    await apiClient.dio.put(
      '/api/payments/order/$orderId/upi/confirm',
      queryParameters: {if (transactionId != null && transactionId.isNotEmpty) 'transactionId': transactionId},
    );
  }

  // --- Notifications ---

  /// Store-wide announcement - one real notification per active customer.
  /// Returns the backend's own confirmation message (e.g. "Sent to 5
  /// customers") rather than discarding it.
  Future<String> broadcastNotification({required String title, required String message}) async {
    final response = await apiClient.dio.post('/api/notifications/broadcast', data: {'title': title, 'message': message});
    return response.data as String;
  }

  // --- Delivery assignment (manual fallback) ---

  /// Every order should already get auto-assigned when placed - this is the
  /// manual fallback for the rare case where no delivery partner happened
  /// to be available at that moment (see DeliveryService.autoAssignBestEffort).
  Future<List<DeliveryPartnerModel>> getAvailablePartners() async {
    final response = await apiClient.dio.get('/api/delivery-partners/available');
    return (response.data as List).map((e) => DeliveryPartnerModel.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> assignDeliveryPartner({required int orderId, required int deliveryPartnerId}) async {
    await apiClient.dio.post(
      '/api/deliveries/assign',
      queryParameters: {'orderId': orderId, 'deliveryPartnerId': deliveryPartnerId},
    );
  }
}
