import 'package:freezed_annotation/freezed_annotation.dart';

part 'product_models.freezed.dart';
part 'product_models.g.dart';

@freezed
class Category with _$Category {
  const factory Category({
    required int id,
    required String name,
    String? description,
    String? imageUrl,
    double? gstRate,
    @Default(true) bool active,
  }) = _Category;

  factory Category.fromJson(Map<String, dynamic> json) => _$CategoryFromJson(json);
}

/// Mirrors backend's ProductVariant exactly. costPrice is intentionally
/// absent here - the backend marks it WRITE_ONLY (admin-settable, never
/// returned), so it will never actually be present in a response to parse.
@freezed
class ProductVariant with _$ProductVariant {
  const factory ProductVariant({
    required int id,
    double? quantity,
    String? unit,
    String? imageUrl,
    required bool available,
    double? mrp,
    required double sellingPrice,
    int? displayOrder,
  }) = _ProductVariant;

  factory ProductVariant.fromJson(Map<String, dynamic> json) => _$ProductVariantFromJson(json);
}

@freezed
class Product with _$Product {
  const factory Product({
    required int id,
    required String name,
    String? brand,
    Category? category,
    @Default([]) List<ProductVariant> variants,
    @Default(true) bool active,

    /// Gallery images for the detail page, in display order.
    ///
    /// Defaults to empty and STAYS empty for products fetched from list
    /// endpoints - those deliberately return no gallery, because pulling five
    /// URLs per card to render one thumbnail is bandwidth nobody sees. Only
    /// the detail endpoint populates this.
    ///
    /// Empty is also the correct, non-broken state for a product that simply
    /// has no gallery yet: the detail page falls back to the variant
    /// thumbnail rather than showing an empty strip.
    @Default([]) List<String> images,
  }) = _Product;

  const Product._();

  factory Product.fromJson(Map<String, dynamic> json) => _$ProductFromJson(json);

  /// The variant shown on a product CARD (list view) - lowest displayOrder,
  /// falling back to the first available one. Full variant SELECTION (e.g.
  /// choosing 500g vs 1kg) belongs on the product detail screen, not built
  /// yet - this is deliberately just "what to show in a list".
  ProductVariant? get primaryVariant {
    if (variants.isEmpty) return null;
    final available = variants.where((v) => v.available).toList();
    // Always a fresh copy, never `variants` itself. Two reasons, and the
    // second is the one that actually crashed: sorting in place would reorder
    // the model's own list as a side effect of reading a getter, and the list
    // a freezed model is deserialised with is UNMODIFIABLE - so aliasing it
    // here threw "Cannot modify an unmodifiable list" for any product whose
    // variants are all out of stock, which is the ordinary sold-out case.
    final pool = available.isNotEmpty ? available : variants.toList();
    pool.sort((a, b) => (a.displayOrder ?? 0).compareTo(b.displayOrder ?? 0));
    return pool.first;
  }

  /// Discount percentage off MRP for the primary variant, or null if there's
  /// no MRP set (nothing to discount against) or it's not actually a discount.
  int? get discountPercent {
    final variant = primaryVariant;
    if (variant == null || variant.mrp == null || variant.mrp! <= variant.sellingPrice) {
      return null;
    }
    return (((variant.mrp! - variant.sellingPrice) / variant.mrp!) * 100).round();
  }
}

enum DiscountType {
  @JsonValue('FLAT')
  flat,
  @JsonValue('PERCENTAGE')
  percentage,
}

@freezed
class Coupon with _$Coupon {
  const factory Coupon({
    required int id,
    required String couponCode,
    required DiscountType discountType,
    required double discountValue,
    double? maxDiscountAmount,
    double? minimumOrderAmount,
  }) = _Coupon;

  factory Coupon.fromJson(Map<String, dynamic> json) => _$CouponFromJson(json);
}
