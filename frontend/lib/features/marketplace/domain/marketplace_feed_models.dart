/// How a customer obtains what a shop has listed.
///
/// Mirrors the backend's CommerceMode. The app must never decide this for
/// itself: whether something can be put in a basket is the server's answer,
/// and [MarketplaceCard.addable] carries it so no screen has to guess.
enum CommerceMode {
  buyOnline,
  visitToBuy,
  serviceAtShop;

  /// Parses an authoritative commerce mode.
  ///
  /// Missing/unknown must fail the response. Treating either as Buy Online
  /// can turn an in-person item or service into cart inventory, which is a
  /// worse failure than an observable feed error.
  static CommerceMode fromWire(String? raw) {
    switch (raw) {
      case 'ONLINE_PURCHASE':
        return CommerceMode.buyOnline;
      case 'VISIT_TO_BUY':
        return CommerceMode.visitToBuy;
      case 'SERVICE_AT_SHOP':
        return CommerceMode.serviceAtShop;
      default:
        throw FormatException('Missing or unsupported commerceMode');
    }
  }

  String get wire {
    switch (this) {
      case CommerceMode.buyOnline:
        return 'ONLINE_PURCHASE';
      case CommerceMode.visitToBuy:
        return 'VISIT_TO_BUY';
      case CommerceMode.serviceAtShop:
        return 'SERVICE_AT_SHOP';
    }
  }

  String get label {
    switch (this) {
      case CommerceMode.buyOnline:
        return 'Buy Online';
      case CommerceMode.visitToBuy:
        return 'Visit to Buy';
      case CommerceMode.serviceAtShop:
        return 'Service at Shop';
    }
  }
}

/// How honest a price is allowed to be.
///
/// Almost nothing bought in person has one committed number - gold moves
/// daily, a sofa depends on the fabric, a repair depends on what is wrong -
/// so a card says what KIND of number it is showing rather than implying a
/// promise the merchant never made.
enum ListingPriceMode {
  exact,
  startingFrom,
  range,
  askAtShop;

  static ListingPriceMode fromWire(String? raw) {
    switch (raw) {
      case 'STARTING_FROM':
        return ListingPriceMode.startingFrom;
      case 'PRICE_RANGE':
        return ListingPriceMode.range;
      case 'ASK_AT_SHOP':
        return ListingPriceMode.askAtShop;
      default:
        return ListingPriceMode.exact;
    }
  }
}

/// One card on the marketplace home screen: a product, and the shop this
/// feed suggests for it.
class MarketplaceCard {
  const MarketplaceCard({
    required this.productId,
    required this.name,
    required this.commerceMode,
    required this.priceMode,
    required this.addable,
    this.brand,
    this.categoryId,
    this.categoryName,
    this.imageUrl,
    this.productVariantId,
    this.variantQuantity,
    this.variantUnit,
    this.sellingPrice,
    this.mrp,
    this.priceMax,
    this.offlineAvailability,
    this.serviceDurationMinutes,
    this.shopId,
    this.shopName,
    this.distanceKm,
    this.sellerCount = 1,
  });

  final int productId;
  final String name;
  final String? brand;
  final int? categoryId;
  final String? categoryName;
  final String? imageUrl;

  final int? productVariantId;
  final double? variantQuantity;
  final String? variantUnit;

  final double? sellingPrice;
  final double? mrp;
  final double? priceMax;
  final ListingPriceMode priceMode;

  final CommerceMode commerceMode;

  /// THE SERVER'S ANSWER, not this app's opinion. A screen that decided for
  /// itself whether to draw ADD would eventually disagree with the backend,
  /// and the direction it would disagree in is showing a buy button for
  /// something that cannot be bought.
  final bool addable;

  final String? offlineAvailability;
  final int? serviceDurationMinutes;

  final int? shopId;
  final String? shopName;
  final double? distanceKm;

  /// How many nearby shops offer this, so the card can say "3 shops nearby"
  /// without a second request.
  final int sellerCount;

  factory MarketplaceCard.fromJson(Map<String, dynamic> json) {
    return MarketplaceCard(
      productId: (json['productId'] as num).toInt(),
      name: (json['name'] as String?) ?? 'Item',
      brand: json['brand'] as String?,
      categoryId: (json['categoryId'] as num?)?.toInt(),
      categoryName: json['categoryName'] as String?,
      imageUrl: json['imageUrl'] as String?,
      productVariantId: (json['productVariantId'] as num?)?.toInt(),
      variantQuantity: (json['variantQuantity'] as num?)?.toDouble(),
      variantUnit: json['variantUnit'] as String?,
      sellingPrice: (json['sellingPrice'] as num?)?.toDouble(),
      mrp: (json['mrp'] as num?)?.toDouble(),
      priceMax: (json['priceMax'] as num?)?.toDouble(),
      priceMode: ListingPriceMode.fromWire(json['priceMode'] as String?),
      commerceMode: CommerceMode.fromWire(json['commerceMode'] as String?),
      addable: (json['addable'] as bool?) ?? false,
      offlineAvailability: json['offlineAvailability'] as String?,
      serviceDurationMinutes: (json['serviceDurationMinutes'] as num?)?.toInt(),
      shopId: (json['shopId'] as num?)?.toInt(),
      shopName: json['shopName'] as String?,
      distanceKm: (json['distanceKm'] as num?)?.toDouble(),
      sellerCount: (json['sellerCount'] as num?)?.toInt() ?? 1,
    );
  }

  /// What to draw where a price goes, in the merchant's own terms.
  String priceLabel() {
    final price = sellingPrice;
    switch (priceMode) {
      case ListingPriceMode.askAtShop:
        return 'Ask at shop';
      case ListingPriceMode.startingFrom:
        return price == null ? 'Ask at shop' : 'From ₹${_money(price)}';
      case ListingPriceMode.range:
        if (price == null) return 'Ask at shop';
        final top = priceMax;
        return top == null
            ? '₹${_money(price)}'
            : '₹${_money(price)} – ₹${_money(top)}';
      case ListingPriceMode.exact:
        return price == null ? 'Ask at shop' : '₹${_money(price)}';
    }
  }

  /// Only an exact price is a promise. Anything else must not be drawn as one.
  bool get priceIsCommitted => priceMode == ListingPriceMode.exact;

  static String _money(double value) {
    return value == value.roundToDouble()
        ? value.toStringAsFixed(0)
        : value.toStringAsFixed(2);
  }
}
