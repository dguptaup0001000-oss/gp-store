import 'package:image_picker/image_picker.dart';

import '../../../core/api/api_client.dart';
import '../../../core/images/image_upload_service.dart';
import '../../products/domain/product_models.dart';
import '../domain/admin_coupon_models.dart';
import '../domain/admin_customer_model.dart';
import '../domain/admin_customer_detail_model.dart';
import '../domain/admin_payment_model.dart';
import '../domain/presence_model.dart';
import '../domain/admin_review_model.dart';
import '../domain/analytics_models.dart';
import '../domain/audit_log_model.dart';
import '../domain/delivery_breach_model.dart';
import '../domain/delivery_partner_models.dart';
import '../../orders/domain/order_models.dart';
import '../domain/inventory_models.dart';
import '../domain/catalogue_item.dart';
import '../domain/listing_engagement.dart';
import '../domain/selling_mode.dart';
import '../domain/variant_attribute.dart';
import '../domain/shop_category.dart';

class AdminProductsRepository {
  AdminProductsRepository({required this.apiClient})
      : _uploads = ImageUploadService(apiClient: apiClient);

  final ApiClient apiClient;
  final ImageUploadService _uploads;

  /// Includes deactivated products too, unlike the customer-facing list.
  /// Live concurrent-user count for the dashboard.
  ///
  /// Deliberately NOT wrapped in a try/catch that returns zero: the backend
  /// already distinguishes "nobody is here" from "I could not count", and
  /// swallowing a transport failure into 0 would throw that distinction away
  /// at the last step. A thrown error surfaces as the panel's unavailable
  /// state instead.
  Future<PresenceSnapshot> getPresence() async {
    final response = await apiClient.dio.get('/api/admin/presence');
    return PresenceSnapshot.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }

  Future<List<Product>> getAllForAdmin() async {
    final response = await apiClient.dio.get('/api/products/admin/all');
    return (response.data as List)
        .map((e) => Product.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Adds something this shop sells: the catalogue entry, its first variant,
  /// this shop's listing and its opening stock, in one server-side transaction.
  ///
  /// POSTS TO /api/shop/products, NOT /api/products, and the difference is the
  /// whole bug. /api/products is the PLATFORM's catalogue - in a marketplace it
  /// answers 403 to anyone without CATALOG_DEFINE, and where it does let a
  /// shopkeeper through (single-shop mode, which is what production still runs)
  /// it writes a catalogue row with no variant and no listing. The merchant's
  /// Products list shows what THIS shop lists, so such a product was invisible
  /// to the person who had just created it, permanently, with no error.
  ///
  /// [firstVariant] carries the half that makes it real. The label is free text
  /// because a variant means something different in every trade - "12 GB +
  /// 256 GB", "Red, pure silk", "1 kg", "Half plate".
  Future<Product> createProduct({
    required String name,
    String? brand,
    required int categoryId,
    required AdminFirstVariant firstVariant,
  }) async {
    final response = await apiClient.dio.post('/api/shop/products', data: {
      'name': name,
      'brand': brand,
      'categoryId': categoryId,
      'active': true,
      'firstVariant': firstVariant.toJson(),
    });
    return Product.fromJson(response.data as Map<String, dynamic>);
  }

  /// SAVES TO THE SHOP'S OWN ROUTE, not the platform catalogue.
  ///
  /// `PUT /api/products/{id}` is the marketplace's shared catalogue and needs
  /// CATALOG_DEFINE once a second shop trades, so a merchant editing a product
  /// his own shop sells got 403 "You don't have permission to do that" - on a
  /// screen that had just rendered the product, because his shelf really does
  /// list it. Active applies to this shop's listings; name, brand and category
  /// are catalogue-wide and the server accepts them only while this shop is
  /// the only one selling the product.
  Future<void> updateProduct({
    required int productId,
    required String name,
    String? brand,
    required int categoryId,
    required bool active,
  }) async {
    await apiClient.dio.put('/api/shop/products/$productId', data: {
      'name': name,
      'brand': brand,
      'categoryId': categoryId,
      'active': active,
    });
  }

  Future<void> deactivateProduct(int productId) async {
    await apiClient.dio.delete('/api/products/$productId');
  }

  /// costPrice/sku/barcode/gstRateOverride are write-only fields the
  /// customer-facing ProductVariant model never returns (see
  /// ProductVariant.costPrice's @JsonProperty WRITE_ONLY on the backend) -
  /// this is why variant creation takes plain named parameters here instead
  /// of reusing that read model.
  /// Creates a variant and returns its new id.
  ///
  /// The id is returned because photos are attached in a second call - they
  /// live in their own table and a variant has to exist before anything can
  /// point at it. Returning void meant the caller had no way to say which
  /// variant the photos it just uploaded belonged to.
  Future<int> createVariant({
    required int productId,
    String? label,
    double? quantity,
    String? unit,
    String? imageUrl,
    required double mrp,
    required double sellingPrice,
    double? costPrice,
    List<VariantAttribute> attributes = const [],
    bool allowBelowCost = false,
    SellingSetup? selling,
  }) async {
    final response = await apiClient.dio.post(
      '/api/shop/products/$productId/variants',
      data: {
        ...?selling?.toJson(),
        'label': label,
        'quantity': quantity,
        'unit': unit,
        'imageUrl': imageUrl,
        'mrp': mrp,
        'sellingPrice': sellingPrice,
        'costPrice': costPrice,
        'available': true,
        'active': true,
        'attributes': attributes.map((a) => a.toJson()).toList(),
      },
    );
    return ((response.data as Map)['productVariantId'] as num).toInt();
  }

  Future<void> updateVariant({
    required int variantId,
    String? label,
    double? quantity,
    String? unit,
    String? imageUrl,
    required double mrp,
    required double sellingPrice,
    double? costPrice,
    required bool available,
    List<VariantAttribute> attributes = const [],
    bool allowBelowCost = false,
    SellingSetup? selling,
  }) async {
    await apiClient.dio.put(
      '/api/shop/variants/$variantId',
      data: {
        ...?selling?.toJson(),
        'label': label,
        'quantity': quantity,
        'unit': unit,
        'imageUrl': imageUrl,
        'mrp': mrp,
        'sellingPrice': sellingPrice,
        'costPrice': costPrice,
        'available': available,
        'active': true,
        'attributes': attributes.map((a) => a.toJson()).toList(),
      },
    );
  }

  /// This shop's shelf, filtered by how the items are sold.
  ///
  /// SERVER-SIDE FILTERING AND PAGING. The Visit-to-Buy screen asks for that
  /// mode and gets that mode; it never downloads the whole catalogue and
  /// filters on the phone, which on a shop with a few thousand listings is
  /// both slow and pointless.
  Future<CataloguePage> catalogue({
    required SellingMode mode,
    String? query,
    int page = 0,
    int size = 30,
  }) async {
    final response = await apiClient.dio.get(
      '/api/shop/catalogue',
      queryParameters: {
        'mode': mode.wire,
        if (query != null && query.trim().isNotEmpty) 'q': query.trim(),
        'page': page,
        'size': size,
      },
    );
    final data = response.data;
    if (data is! Map) return CataloguePage.empty;
    return CataloguePage.fromJson(Map<String, dynamic>.from(data));
  }

  /// How many listings this shop has in each mode, for the nav counts.
  Future<Map<String, int>> catalogueCounts() async {
    final response = await apiClient.dio.get('/api/shop/catalogue/counts');
    final data = response.data;
    if (data is! Map) return const {};
    return data.map((key, value) =>
        MapEntry('$key', value is num ? value.toInt() : 0));
  }

  /// Categories for a picker, most relevant to THIS shop first.
  ///
  /// The relevance ordering is the server's, not this app's: it knows which
  /// categories the shop already sells in, and computing that here would mean
  /// downloading the shelf to sort a dropdown.
  Future<List<CategoryOption>> searchCategories({String? query, int limit = 40}) async {
    final response = await apiClient.dio.get(
      '/api/shop/category-search',
      queryParameters: {
        if (query != null && query.trim().isNotEmpty) 'q': query.trim(),
        'limit': limit,
      },
    );
    final data = response.data;
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((e) => CategoryOption.fromJson(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }

  /// What this shop's offline listings attracted, over a window.
  ///
  /// INTEREST, NOT SALES, and the server says so in the payload rather than
  /// leaving it to this app to remember.
  Future<ListingEngagementReport> getListingEngagement({int days = 30}) async {
    final now = DateTime.now();
    final response = await apiClient.dio.get(
      '/api/shop/engagement',
      queryParameters: {
        'from': now.subtract(Duration(days: days)).toIso8601String(),
        'to': now.toIso8601String(),
      },
    );
    final data = response.data;
    if (data is! Map<String, dynamic>) return ListingEngagementReport.empty;
    return ListingEngagementReport.fromJson(data);
  }

  /// One of this shop's variants, with the attributes it already carries.
  Future<List<VariantAttribute>> getVariantAttributes(int variantId) async {
    final response = await apiClient.dio.get('/api/shop/variants/$variantId');
    final data = response.data as Map<String, dynamic>;
    final raw = (data['attributes'] as List?) ?? const [];
    return raw
        .map((e) => VariantAttribute.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Lets the admin pick a photo from their gallery. Bytes go to object
  /// storage with a short-lived signed URL from this backend. The API
  /// never stores R2 secrets in the app, and the UI does not name the
  /// storage vendor.
  static const int maxVariantImages = 5;

  /// The longest edge any uploaded photo is allowed to have, in pixels.
  ///
  /// A modern phone camera produces something like 4000x3000 and four
  /// megabytes. Nothing in this app ever displays a grocery photo larger than
  /// a phone screen, so uploading the original would cost the shopkeeper's
  /// data to store pixels the customer's data then pays to download again.
  /// 1600 is generous for a full-screen gallery view on a high-density phone
  /// and roughly a tenth of the bytes.
  static const double _maxImageEdge = 1600;

  /// JPEG quality. 85 is the usual "you cannot see the difference on a
  /// photograph" point; below about 75 the compression starts showing on
  /// packaging text, which is exactly what these photos are of.
  static const int _imageQuality = 85;

  Future<String?> pickAndUploadVariantImage({int? ownerId}) {
    return _uploads.pickAndUpload(
      kind: CatalogImageKind.product,
      ownerId: ownerId,
      maxImageEdge: _maxImageEdge,
      imageQuality: _imageQuality,
    );
  }

  /// Picks several photos at once and uploads them, in the order picked.
  ///
  /// [remaining] is how many the variant can still take. The picker itself
  /// cannot be told "at most three", so the list is trimmed after the fact -
  /// which is why this returns how many were dropped, so the caller can say
  /// so rather than silently ignoring the admin's last two taps.
  ///
  /// One signed PUT per file, sequential. Parallel uploads from a shop phone
  /// on rural mobile data is how they all time out.
  Future<({List<String> urls, int skipped})> pickAndUploadVariantImages({
    required int remaining,
    int? ownerId,
  }) async {
    if (remaining <= 0) {
      return (urls: const <String>[], skipped: 0);
    }

    final picked = await ImagePicker().pickMultiImage(
      imageQuality: _imageQuality,
      maxWidth: _maxImageEdge,
      maxHeight: _maxImageEdge,
    );
    if (picked.isEmpty) {
      return (urls: const <String>[], skipped: 0);
    }

    final accepted =
        picked.length > remaining ? picked.sublist(0, remaining) : picked;
    final skipped = picked.length - accepted.length;

    final urls = <String>[];
    for (final file in accepted) {
      urls.add(await _uploads.uploadPickedFile(
        file,
        kind: CatalogImageKind.product,
        ownerId: ownerId,
      ));
    }

    return (urls: urls, skipped: skipped);
  }

  /// This variant's photos, in order.
  Future<List<String>> getVariantImages(int variantId) async {
    final response =
        await apiClient.dio.get('/api/shop/variants/$variantId/images');
    return ((response.data as List?) ?? const [])
        .map((e) => e.toString())
        .toList(growable: false);
  }

  /// Replaces a variant's photos with exactly this list, in this order.
  ///
  /// The whole list, not one image - the first entry is the primary photo, so
  /// order carries meaning, and add/remove/reorder as three calls is three
  /// chances for the screen's order and the server's to drift apart.
  Future<List<String>> setVariantImages(
      int variantId, List<String> urls) async {
    final response = await apiClient.dio.put(
      '/api/shop/variants/$variantId/images',
      data: {'imageUrls': urls},
    );
    return ((response.data as List?) ?? const [])
        .map((e) => e.toString())
        .toList(growable: false);
  }

  /// The departments THIS shop actually trades in.
  ///
  /// SEPARATE FROM [getCategories] because they answer different questions, and
  /// answering the second with the first is what showed a newly onboarded phone
  /// shop a management list of "Atta, Rice & Dal ... for everyday kirana needs".
  /// The taxonomy is the platform's; the shelf is the shop's.
  Future<List<Category>> getMyCategories() async {
    final response = await apiClient.dio.get('/api/categories/mine');
    return (response.data as List)
        .map((e) => Category.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// The whole platform taxonomy - what Add Product offers to choose from.
  Future<List<Category>> getCategories() async {
    final response = await apiClient.dio.get('/api/categories');
    return (response.data as List)
        .map((e) => Category.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// THIS SHOP'S OWN DEPARTMENTS, which is a different table from the
  /// platform taxonomy [getCategories] returns.
  ///
  /// The Add Category button used to post to the platform tree, which needs
  /// CATALOG_DEFINE once a second merchant is trading - so on a real phone it
  /// always answered 403 and the screen said "You don't have permission to do
  /// that". A merchant organising their own shelf is not editing the
  /// marketplace's taxonomy, and now has somewhere of their own to do it.
  Future<List<ShopCategory>> getShopCategories() async {
    final response = await apiClient.dio.get('/api/shop/categories');
    return ((response.data as List?) ?? const [])
        .map((e) => ShopCategory.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<ShopCategory> createCategory(
      {required String name, String? description, double? gstRate}) async {
    final response = await apiClient.dio.post('/api/shop/categories', data: {
      'name': name,
      'description': description,
      'active': true,
    });
    return ShopCategory.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> renameShopCategory({
    required int shopCategoryId,
    required String name,
    String? description,
    bool? active,
  }) async {
    await apiClient.dio.put('/api/shop/categories/$shopCategoryId', data: {
      'name': name,
      'description': description,
      'active': active,
    });
  }

  Future<void> removeShopCategory(int shopCategoryId) async {
    await apiClient.dio.delete('/api/shop/categories/$shopCategoryId');
  }

  /// imageUrl is omitted on purpose. The backend keeps the existing photo
  /// when the field is null; sending null used to wipe it.
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
  Future<({List<InventoryItem> items, int totalPages})> getAllInventory(
      {int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/inventory',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      items: content
          .map((e) => InventoryItem.fromJson(e as Map<String, dynamic>))
          .toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  Future<List<InventoryItem>> getLowStock() async {
    final response = await apiClient.dio.get('/api/inventory/low-stock');
    return (response.data as List)
        .map((e) => InventoryItem.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Additive - "we received N more units", not a replacement value. Matches
  /// the backend's restock endpoint exactly.
  Future<void> restock(
      {required int inventoryId, required int quantity}) async {
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
    return (response.data as List)
        .map((e) => AdminCoupon.fromJson(e as Map<String, dynamic>))
        .toList();
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
      'discountType': discountType.apiName,
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
      'discountType': discountType.apiName,
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
    return (response.data as List)
        .map((e) => DeliveryPartnerModel.fromJson(e as Map<String, dynamic>))
        .toList();
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
    assert(
        partner.id != null, 'Cannot update a delivery partner without an id');
    await apiClient.dio.put('/api/delivery-partners', data: partner.toJson());
  }

  // --- Analytics ---

  Future<SalesSummary> getSalesSummary({int days = 30}) async {
    final response = await apiClient.dio
        .get('/api/analytics/sales-summary', queryParameters: {'days': days});
    return SalesSummary.fromJson(response.data as Map<String, dynamic>);
  }

  /// Daily revenue for the dashboard chart.
  ///
  /// Every day in the window is present, gaps already filled server-side -
  /// see AnalyticsService.getSalesSeries. Plot this positionally; do not
  /// drop the zero days or the chart stops telling the truth about the
  /// shape of the week.
  Future<List<SalesPoint>> getSalesSeries({int days = 30}) async {
    final response = await apiClient.dio
        .get('/api/analytics/sales-series', queryParameters: {'days': days});
    return (response.data as List)
        .map((e) => SalesPoint.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Map values come back as JSON numbers (Long serializes as a plain
  /// number, not a string) - cast explicitly rather than assume int.
  Future<Map<String, int>> getOrderStatusBreakdown() async {
    final response =
        await apiClient.dio.get('/api/analytics/order-status-breakdown');
    return (response.data as Map<String, dynamic>)
        .map((key, value) => MapEntry(key, (value as num).toInt()));
  }

  Future<List<TopProduct>> getTopProducts(
      {int days = 30, int limit = 10}) async {
    final response = await apiClient.dio.get(
      '/api/analytics/top-products',
      queryParameters: {'days': days, 'limit': limit},
    );
    return (response.data as List)
        .map((e) => TopProduct.fromJson(e as Map<String, dynamic>))
        .toList();
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
    return (response.data as List)
        .map((e) => DeliveryBreach.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  // --- Audit log ---

  Future<List<AuditLogEntry>> getAuditLog({int page = 0, int size = 50}) async {
    final response = await apiClient.dio.get(
      '/api/audit-logs',
      queryParameters: {'page': page, 'size': size},
    );
    final content = response.data['content'] as List;
    return content
        .map((e) => AuditLogEntry.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  // --- Orders ---

  /// Every order in the system, WITH customer name (a raw entity dump
  /// alone can't show this - Order.customer is hidden from JSON). Order
  /// detail viewing reuses the existing customer-facing orderDetailProvider
  /// directly - the backend now allows admin to bypass the ownership check
  /// on that same endpoint, so no separate admin detail call is needed.
  /// Paginated - every order ever placed, system-wide, has no natural upper bound.
  Future<({List<OrderSummary> orders, int totalPages})> getAllOrders(
      {int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/orders/admin/all',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      orders: content
          .map((e) => OrderSummary.fromJson(e as Map<String, dynamic>))
          .toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  /// A specific customer's order history - for support/dispute lookups
  /// ("this customer says their order never arrived"). Backend now
  /// paginates this (it used to load a customer's entire order history
  /// unbounded) - this screen has no infinite-scroll UI of its own, so it
  /// just asks for the most recent 100, which comfortably covers what a
  /// support lookup actually needs.
  Future<List<OrderSummary>> getCustomerOrders(int customerId) async {
    final response = await apiClient.dio.get(
      '/api/orders/customer/$customerId',
      queryParameters: {'page': 0, 'size': 100},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return content
        .map((e) => OrderSummary.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<void> updateOrderStatus(
      {required int orderId, required String status}) async {
    await apiClient.dio.put(
      '/api/orders/$orderId/status',
      queryParameters: {'status': status},
    );
  }

  // --- Review moderation ---

  /// Paginated - system-wide review count has no natural upper bound.
  Future<({List<AdminReview> reviews, int totalPages})> getAllReviews(
      {int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/reviews',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      reviews: content
          .map((e) => AdminReview.fromJson(e as Map<String, dynamic>))
          .toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  /// Moderation delete - removes ANY review, not just the admin's own.
  Future<void> moderateDeleteReview(int reviewId) async {
    await apiClient.dio.delete('/api/reviews/$reviewId/moderate');
  }

  // --- Customer management ---

  /// Paginated - the customer base has no natural upper bound.
  Future<({List<AdminCustomer> customers, int totalPages})> getAllCustomers(
      {int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/customers',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      customers: content
          .map((e) => AdminCustomer.fromJson(e as Map<String, dynamic>))
          .toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  /// The whole file on one customer, in a single round trip.
  ///
  /// Staff-only on the backend (CUSTOMERS_VIEW). Stitching this together
  /// client-side from the customer, address, cart, wishlist and order
  /// endpoints would give a shop counter five chances to half-load the
  /// screen; one call is either right or absent.
  Future<AdminCustomerDetail> getCustomerDetail(int customerId) async {
    final response = await apiClient.dio.get('/api/customers/$customerId/detail');
    return AdminCustomerDetail.fromJson(response.data as Map<String, dynamic>);
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
  Future<void> setCustomerActive(
      {required int customerId, required bool active}) async {
    await apiClient.dio.put(
      '/api/customers/$customerId/active',
      queryParameters: {'active': active},
    );
  }

  // --- Payments ---

  /// Paginated - system-wide payment count has no natural upper bound.
  Future<({List<AdminPayment> payments, int totalPages})> getAllPayments(
      {int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/payments',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      payments: content
          .map((e) => AdminPayment.fromJson(e as Map<String, dynamic>))
          .toList(),
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
      queryParameters: {
        if (transactionId != null && transactionId.isNotEmpty)
          'transactionId': transactionId
      },
    );
  }

  // --- Notifications ---

  /// An announcement to the shop's own customers - everyone who has ordered
  /// from it - as one real notification each, plus a push where the device is
  /// registered.
  ///
  /// NOT STORE-WIDE ANY MORE, and it should never have been. The server used
  /// to push this to the platform's global FCM topic and write a row for every
  /// active customer on GP-STORE whoever asked, so a shop that opened this
  /// morning could notify the whole marketplace. The reach is now decided by
  /// the shop in scope; a platform admin still reaches everybody.
  ///
  /// Returns the backend's own confirmation message (e.g. "Sent to 5
  /// customers") rather than discarding it.
  Future<String> broadcastNotification(
      {required String title, required String message}) async {
    final response = await apiClient.dio.post('/api/notifications/broadcast',
        data: {'title': title, 'message': message});
    return response.data as String;
  }

  // --- Delivery assignment (manual fallback) ---

  /// Every order should already get auto-assigned when placed - this is the
  /// manual fallback for the rare case where no delivery partner happened
  /// to be available at that moment (see DeliveryService.autoAssignBestEffort).
  Future<List<DeliveryPartnerModel>> getAvailablePartners() async {
    final response =
        await apiClient.dio.get('/api/delivery-partners/available');
    return (response.data as List)
        .map((e) => DeliveryPartnerModel.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<void> assignDeliveryPartner(
      {required int orderId, required int deliveryPartnerId}) async {
    await apiClient.dio.post(
      '/api/deliveries/assign',
      queryParameters: {
        'orderId': orderId,
        'deliveryPartnerId': deliveryPartnerId
      },
    );
  }
}

/// The first sellable form of a new product, and what this shop asks for it.
///
/// DELIBERATELY NOT GROCERY-SHAPED. GP-STORE sells phones, sarees, medicine and
/// atta, and "pack size" is a kirana word. [label] is whatever distinguishes one
/// sellable thing from another in the merchant's own trade; the server stores it
/// against the same generic variant columns every product already uses, so
/// nothing about the existing variant architecture changes.
class AdminFirstVariant {
  const AdminFirstVariant({
    required this.label,
    required this.sellingPrice,
    this.mrp,
    this.stock,
    this.selling,
  });

  final String label;
  final double sellingPrice;
  final double? mrp;
  final int? stock;

  /// HOW this shop sells it. Absent means Buy Online, which is what every
  /// product created before commerce modes existed was, so an older caller
  /// that does not send it keeps behaving exactly as it did.
  final SellingSetup? selling;

  Map<String, dynamic> toJson() => {
        if (label.trim().isNotEmpty) 'label': label.trim(),
        'sellingPrice': sellingPrice,
        if (mrp != null) 'mrp': mrp,
        'stock': stock ?? 0,
        ...?selling?.toJson(),
      };
}
