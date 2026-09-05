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

    /// This variant's own photos, in order. First is the primary one.
    ///
    /// EMPTY ON EVERY LIST RESPONSE, and that is the server being deliberate
    /// rather than this model being wrong: a browse grid renders one
    /// thumbnail per card from [imageUrl], and carrying five URLs per variant
    /// through twenty cards would be payload nobody looks at. The detail
    /// endpoint is the only one that fills this in.
    ///
    /// Also empty for every variant nobody has photographed, which is every
    /// variant that existed before this feature - the gallery falls back to
    /// [imageUrl] for those, exactly as it did.
    @Default([]) List<String> images,
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

    /// URL of an optional GLB/GLTF model.
    ///
    /// Null for almost every product and that is the expected state, not a
    /// gap - photographing a bag of atta is cheap, modelling one is not. It
    /// is also null on every LIST response by design: the backend attaches
    /// it on the detail endpoint only, so a feed page never carries a field
    /// one screen reads.
    ///
    /// [has3dModel] below is the only thing the UI should test. Nothing about
    /// 3D appears anywhere until a product actually has a model.
    String? model3dUrl,

    /// Grouping within a category ("Atta" inside "Atta, Rice & Dal").
    ///
    /// Null on every product the shop entered by hand before the catalog
    /// work, and that is fine - nothing renders it as a required field.
    String? subcategory,

    /// Curated flags from the catalog. Absent on older backends, so both
    /// default to false rather than being required.
    @Default(false) bool bestseller,
    @Default(false) bool featured,

    /// True when this product's price is seeded test data that nobody has
    /// checked against a shelf.
    ///
    /// Read by the admin screens only. It is deliberately NOT surfaced to
    /// customers: a shopper does not need to be told the shop is still
    /// setting itself up, and a badge saying so on a live listing would do
    /// more harm than the information is worth.
    @Default(false) bool testData,

    /// How many pack sizes this product has - NOT how many are in [variants].
    ///
    /// A browse, search or feed card carries exactly ONE variant on purpose
    /// (see backend ProductResponse.fromCard: a twenty-product page was
    /// serialising a hundred prices no card draws). That trim also removed
    /// the only way a card could know the product comes in 500 g as well as
    /// 1 kg, so the grid quietly added the cheapest pack and never offered
    /// the rest. This integer restores the signal for four bytes.
    ///
    /// Defaults to 0, not 1, so "the server did not say" is distinguishable
    /// from "one size" - an older backend, or an entry cached before the
    /// field existed, omits the key entirely. [optionCount] below is what
    /// the UI should read; it falls back to the variant list's own length,
    /// which is exactly the behaviour the app had before this field.
    @Default(0) int variantCount,
  }) = _Product;

  const Product._();

  /// Whether this product has a 3D model worth offering.
  ///
  /// A getter rather than a null check at each call site so there is exactly
  /// one definition of "has a model" - including rejecting a blank string,
  /// which an admin form will produce sooner or later and which would
  /// otherwise put a "View in 3D" button on a product with nothing to show.
  bool get has3dModel => model3dUrl != null && model3dUrl!.trim().isNotEmpty;

  /// How many sizes to tell the customer about.
  ///
  /// Never smaller than the number of variants actually in hand, so a detail
  /// response - which carries them all and may predate [variantCount] - is
  /// still counted correctly, and an older backend that sends no count at all
  /// degrades to what the app did before rather than claiming "0 options".
  int get optionCount =>
      variantCount > variants.length ? variantCount : variants.length;

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
  @JsonValue('DELIVERY_FLAT')
  deliveryFlat,
}

extension DiscountTypeLabel on DiscountType {
  String get apiName => switch (this) {
        DiscountType.flat => 'FLAT',
        DiscountType.percentage => 'PERCENTAGE',
        DiscountType.deliveryFlat => 'DELIVERY_FLAT',
      };

  String offerLabel(double value) {
    final amount = value.toStringAsFixed(0);
    return switch (this) {
      DiscountType.percentage => '$amount% OFF',
      DiscountType.flat => '₹$amount OFF',
      DiscountType.deliveryFlat => 'Up to ₹$amount off delivery',
    };
  }
}

/// The human-readable conditions of an offer.
///
/// EVERY LINE IS DERIVED FROM A STORED FIELD. There is no marketing copy
/// table behind this and none should be invented: a shopper who reads
/// "Min. order ₹299" and then finds the code rejected at ₹250 has been lied
/// to by the app, so the only safe source for these lines is the same data
/// CouponService validates against.
extension CouponTerms on Coupon {
  /// The headline benefit, e.g. "₹100 OFF" or "10% OFF up to ₹200".
  String get headline {
    final base = discountType.offerLabel(discountValue);
    if (discountType == DiscountType.percentage && maxDiscountAmount != null) {
      return '$base up to ₹${maxDiscountAmount!.toStringAsFixed(0)}';
    }
    return base;
  }

  /// Conditions, one per bullet. Empty when a coupon genuinely has none -
  /// rendering "No conditions apply" would be a claim this data cannot back.
  List<String> get conditions {
    final lines = <String>[];
    if (minimumOrderAmount != null && minimumOrderAmount! > 0) {
      lines.add(
          'Minimum order value ₹${minimumOrderAmount!.toStringAsFixed(0)}');
    }
    if (maxDiscountAmount != null && discountType == DiscountType.percentage) {
      lines.add(
          'Maximum discount ₹${maxDiscountAmount!.toStringAsFixed(0)} per order');
    }
    if (discountType == DiscountType.deliveryFlat) {
      lines.add('Applies to the delivery fee, not the items');
    }
    final expiry = expiryDate;
    if (expiry != null) {
      lines.add('Valid till ${_formatCouponDate(expiry)}');
    }
    return lines;
  }
}

const _couponMonths = <String>[
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

/// "31 Dec 2026". Deliberately not DateFormat: intl is not a dependency of
/// this module and one date needs one line, not a package.
String _formatCouponDate(DateTime date) =>
    '${date.day} ${_couponMonths[date.month - 1]} ${date.year}';

@freezed
class Coupon with _$Coupon {
  const factory Coupon({
    required int id,
    required String couponCode,
    required DiscountType discountType,
    required double discountValue,
    double? maxDiscountAmount,
    double? minimumOrderAmount,

    /// Last day the code works, inclusive. Nullable because a coupon with no
    /// expiry is a real thing the admin form allows - an evergreen offer.
    ///
    /// The server only ever lists coupons that are usable right now
    /// (CouponService.getActiveCoupons filters expired and exhausted ones
    /// out), so this is never in the past on the offers list. It is carried
    /// anyway because "Valid till 31 Dec" is the single condition shoppers
    /// most want to see before deciding whether to use a code today.
    DateTime? expiryDate,
  }) = _Coupon;

  factory Coupon.fromJson(Map<String, dynamic> json) => _$CouponFromJson(json);
}
