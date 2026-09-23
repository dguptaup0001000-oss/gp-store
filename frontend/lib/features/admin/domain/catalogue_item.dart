import 'selling_mode.dart';

/// One line on a merchant's own shelf, as their catalogue screens read it.
///
/// CARRIES THE NAME AND THE MODE, which is the whole reason this exists: the
/// older listings payload was prices and ids, so a screen showing "everything
/// you sell as Visit to Buy" could not be drawn without fetching each product
/// separately. That is the N+1 this project keeps removing.
class CatalogueItem {
  const CatalogueItem({
    required this.productVariantId,
    required this.productId,
    required this.name,
    required this.commerceMode,
    this.brand,
    this.imageUrl,
    this.categoryId,
    this.categoryName,
    this.variantLabel,
    this.sellingPrice,
    this.mrp,
    this.priceMax,
    this.priceMode = PriceMode.exact,
    this.offlineAvailability,
    this.serviceDurationMinutes,
    this.available = true,
    this.active = true,
    this.stock,
  });

  final int productVariantId;
  final int productId;
  final String name;
  final String? brand;
  final String? imageUrl;
  final int? categoryId;
  final String? categoryName;
  final String? variantLabel;

  final double? sellingPrice;
  final double? mrp;
  final double? priceMax;
  final PriceMode priceMode;

  final SellingMode commerceMode;
  final String? offlineAvailability;
  final int? serviceDurationMinutes;

  final bool available;
  final bool active;

  /// Null for a service or a Visit-to-Buy item: counted stock is a question
  /// about things on a shelf, and a haircut has no stock level.
  final int? stock;

  factory CatalogueItem.fromJson(Map<String, dynamic> json) => CatalogueItem(
        productVariantId: (json['productVariantId'] as num).toInt(),
        productId: (json['productId'] as num?)?.toInt() ?? 0,
        name: (json['name'] as String?) ?? 'Item',
        brand: json['brand'] as String?,
        imageUrl: json['imageUrl'] as String?,
        categoryId: (json['categoryId'] as num?)?.toInt(),
        categoryName: json['categoryName'] as String?,
        variantLabel: json['variantLabel'] as String?,
        sellingPrice: (json['sellingPrice'] as num?)?.toDouble(),
        mrp: (json['mrp'] as num?)?.toDouble(),
        priceMax: (json['priceMax'] as num?)?.toDouble(),
        priceMode: PriceMode.fromWire(json['priceMode'] as String?),
        commerceMode: SellingMode.fromRequiredWire(json['commerceMode']),
        offlineAvailability: json['offlineAvailability'] as String?,
        serviceDurationMinutes: (json['serviceDurationMinutes'] as num?)?.toInt(),
        available: (json['available'] as bool?) ?? true,
        active: (json['active'] as bool?) ?? true,
        stock: (json['stock'] as num?)?.toInt(),
      );

  /// What to draw where a price goes, in the merchant's own terms.
  String priceLabel() {
    final price = sellingPrice;
    switch (priceMode) {
      case PriceMode.askAtShop:
        return 'Ask at shop';
      case PriceMode.startingFrom:
        return price == null ? 'Ask at shop' : 'From ₹${_money(price)}';
      case PriceMode.range:
        if (price == null) return 'Ask at shop';
        final top = priceMax;
        return top == null ? '₹${_money(price)}' : '₹${_money(price)} – ₹${_money(top)}';
      case PriceMode.exact:
        return price == null ? 'Ask at shop' : '₹${_money(price)}';
    }
  }

  /// "About 30 min", for a service the merchant timed. Null otherwise.
  String? get durationLabel {
    final minutes = serviceDurationMinutes;
    if (minutes == null || minutes <= 0) return null;
    if (minutes < 60) return 'About $minutes min';
    final hours = minutes ~/ 60;
    final rest = minutes % 60;
    return rest == 0 ? 'About $hours hr' : 'About $hours hr $rest min';
  }

  /// What the merchant said is on the shelf, in words rather than an enum.
  String? get availabilityLabel {
    switch (offlineAvailability) {
      case 'AVAILABLE':
        return 'Available';
      case 'LIMITED_AVAILABILITY':
        return 'Limited';
      case 'MADE_TO_ORDER':
        return 'Made to order';
      case 'OUT_OF_STOCK':
        return 'Out of stock';
      case 'CONTACT_SHOP':
        return 'Call the shop';
      default:
        // A value a newer server knows and this build does not. Dropped
        // rather than printed raw - SOMETHING_NEW on a merchant's screen
        // reads as a broken app.
        return null;
    }
  }

  static String _money(double value) => value == value.roundToDouble()
      ? value.toStringAsFixed(0)
      : value.toStringAsFixed(2);
}

/// A page of a merchant's shelf, with the server's own total.
class CataloguePage {
  const CataloguePage({
    required this.content,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
  });

  final List<CatalogueItem> content;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;

  bool get isEmpty => content.isEmpty;
  bool get hasMore => page + 1 < totalPages;

  static const empty = CataloguePage(
      content: [], page: 0, size: 0, totalElements: 0, totalPages: 0);

  factory CataloguePage.fromJson(Map<String, dynamic> json) {
    final raw = (json['content'] as List?) ?? const [];
    return CataloguePage(
      content: raw
          .whereType<Map>()
          .map((e) => CatalogueItem.fromJson(Map<String, dynamic>.from(e)))
          .toList(growable: false),
      page: (json['page'] as num?)?.toInt() ?? 0,
      size: (json['size'] as num?)?.toInt() ?? 0,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
    );
  }
}

/// One category as the merchant picker draws it.
class CategoryOption {
  const CategoryOption({
    required this.id,
    required this.name,
    this.parentId,
    this.parentName,
    this.imageUrl,
    this.usedByThisShop = false,
    this.childCount = 0,
  });

  final int id;
  final String name;
  final int? parentId;

  /// The parent's NAME, not just its id, so a row reads "Mobile Phones" with
  /// "Electronics" under it without the client resolving ids.
  final String? parentName;
  final String? imageUrl;

  /// Whether this shop already has listings here. Drives the "Your categories"
  /// grouping and the ordering.
  final bool usedByThisShop;
  final int childCount;

  bool get hasChildren => childCount > 0;

  factory CategoryOption.fromJson(Map<String, dynamic> json) => CategoryOption(
        id: (json['id'] as num).toInt(),
        name: (json['name'] as String?) ?? 'Category',
        parentId: (json['parentId'] as num?)?.toInt(),
        parentName: json['parentName'] as String?,
        imageUrl: json['imageUrl'] as String?,
        usedByThisShop: (json['usedByThisShop'] as bool?) ?? false,
        childCount: (json['childCount'] as num?)?.toInt() ?? 0,
      );
}
