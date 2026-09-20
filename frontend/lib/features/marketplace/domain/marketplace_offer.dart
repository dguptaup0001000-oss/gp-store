import 'marketplace_feed_models.dart';

/// One shop's offer of one product: what it costs there, and how to reach it.
///
/// THE OTHER HALF OF THE FEED'S DEDUPLICATION. A card shows one product, not
/// one product per shop, because five shops stocking the same drink is one
/// drink. The moment a customer taps it, "who has it, where, and for how much"
/// is the whole question - and this is that answer. Nothing was hidden by the
/// card and nothing is invented here.
class MarketplaceOffer {
  const MarketplaceOffer({
    required this.productId,
    required this.productName,
    required this.commerceMode,
    required this.priceMode,
    required this.addable,
    this.brand,
    this.productVariantId,
    this.variantQuantity,
    this.variantUnit,
    this.price,
    this.mrp,
    this.priceMax,
    this.offlineAvailability,
    this.serviceDurationMinutes,
    this.shopId,
    this.shopName,
    this.addressLine,
    this.locality,
    this.city,
    this.pincode,
    this.shopLatitude,
    this.shopLongitude,
    this.supportPhone,
    this.distanceKm,
  });

  final int productId;
  final String productName;
  final String? brand;
  final int? productVariantId;
  final double? variantQuantity;
  final String? variantUnit;

  final double? price;
  final double? mrp;
  final double? priceMax;
  final ListingPriceMode priceMode;
  final CommerceMode commerceMode;

  /// THE SERVER'S ANSWER. A screen deciding for itself whether to draw ADD
  /// would eventually disagree with the backend, and the direction it would
  /// disagree in is offering to buy something that cannot be bought.
  final bool addable;

  final String? offlineAvailability;
  final int? serviceDurationMinutes;

  final int? shopId;
  final String? shopName;
  final String? addressLine;
  final String? locality;
  final String? city;
  final String? pincode;
  final double? shopLatitude;
  final double? shopLongitude;
  final String? supportPhone;
  final double? distanceKm;

  factory MarketplaceOffer.fromJson(Map<String, dynamic> json) {
    return MarketplaceOffer(
      productId: (json['productId'] as num).toInt(),
      productName: (json['productName'] as String?) ?? 'Item',
      brand: json['brand'] as String?,
      productVariantId: (json['productVariantId'] as num?)?.toInt(),
      variantQuantity: (json['variantQuantity'] as num?)?.toDouble(),
      variantUnit: json['variantUnit'] as String?,
      price: (json['price'] as num?)?.toDouble(),
      mrp: (json['mrp'] as num?)?.toDouble(),
      priceMax: (json['priceMax'] as num?)?.toDouble(),
      priceMode: ListingPriceMode.fromWire(json['priceMode'] as String?),
      commerceMode: CommerceMode.fromWire(json['commerceMode'] as String?),
      addable: (json['addable'] as bool?) ?? false,
      offlineAvailability: json['offlineAvailability'] as String?,
      serviceDurationMinutes: (json['serviceDurationMinutes'] as num?)?.toInt(),
      shopId: (json['shopId'] as num?)?.toInt(),
      shopName: json['shopName'] as String?,
      addressLine: json['addressLine'] as String?,
      locality: json['locality'] as String?,
      city: json['city'] as String?,
      pincode: json['pincode'] as String?,
      shopLatitude: (json['shopLatitude'] as num?)?.toDouble(),
      shopLongitude: (json['shopLongitude'] as num?)?.toDouble(),
      supportPhone: json['supportPhone'] as String?,
      distanceKm: (json['distanceKm'] as num?)?.toDouble(),
    );
  }

  /// The address on one line, or null when the shop has not filled one in.
  ///
  /// NULL RATHER THAN A PLACEHOLDER. "Address not available" under a Visit to
  /// Buy heading reads as an app failure; no address line at all reads as a
  /// shop that has not said, which is the truth.
  String? get whereToGo {
    final parts = [addressLine, locality, city, pincode]
        .where((p) => p != null && p.trim().isNotEmpty)
        .map((p) => p!.trim());
    return parts.isEmpty ? null : parts.join(', ');
  }

  bool get canBeVisited => shopLatitude != null && shopLongitude != null;

  /// What to draw where a price goes, in the merchant's own terms.
  String priceLabel() {
    final value = price;
    switch (priceMode) {
      case ListingPriceMode.askAtShop:
        return 'Ask at shop';
      case ListingPriceMode.startingFrom:
        return value == null ? 'Ask at shop' : 'From ₹${_money(value)}';
      case ListingPriceMode.range:
        if (value == null) return 'Ask at shop';
        final top = priceMax;
        return top == null
            ? '₹${_money(value)}'
            : '₹${_money(value)} – ₹${_money(top)}';
      case ListingPriceMode.exact:
        return value == null ? 'Ask at shop' : '₹${_money(value)}';
    }
  }

  /// "Available", "Made to order" - what the merchant said is on the shelf.
  String? get availabilityLabel {
    switch (offlineAvailability) {
      case 'AVAILABLE':
        return 'Available at the shop';
      case 'LIMITED_AVAILABILITY':
        return 'Limited availability';
      case 'MADE_TO_ORDER':
        return 'Made to order';
      case 'OUT_OF_STOCK':
        return 'Out of stock right now';
      case 'CONTACT_SHOP':
        return 'Call the shop to check';
      default:
        return null;
    }
  }

  /// "About 30 min" - only for a service, and only when the merchant said so.
  String? get durationLabel {
    final minutes = serviceDurationMinutes;
    if (minutes == null || minutes <= 0) return null;
    if (minutes < 60) return 'About $minutes min';
    final hours = minutes ~/ 60;
    final rest = minutes % 60;
    return rest == 0 ? 'About $hours hr' : 'About $hours hr $rest min';
  }

  String? get distanceLabel {
    final km = distanceKm;
    if (km == null) return null;
    return km < 1 ? '${(km * 1000).round()} m away' : '${km.toStringAsFixed(1)} km away';
  }

  static String _money(double value) {
    return value == value.roundToDouble()
        ? value.toStringAsFixed(0)
        : value.toStringAsFixed(2);
  }
}

/// The offers for one product, split the way the screen draws them.
///
/// GROUPED, NOT FILTERED. A customer who tapped a Visit-to-Buy card and could
/// have had the thing delivered by a shop two streets further should be told
/// so - hiding it because of the card they arrived through answers a narrower
/// question than the one they have, which is "how do I get this?".
class ProductOffers {
  const ProductOffers(this.all);

  final List<MarketplaceOffer> all;

  List<MarketplaceOffer> get deliverable =>
      all.where((o) => o.commerceMode == CommerceMode.buyOnline).toList();

  List<MarketplaceOffer> get visitable =>
      all.where((o) => o.commerceMode == CommerceMode.visitToBuy).toList();

  List<MarketplaceOffer> get services =>
      all.where((o) => o.commerceMode == CommerceMode.serviceAtShop).toList();

  bool get isEmpty => all.isEmpty;

  /// How many distinct shops offer this at all, for "3 shops nearby".
  int get shopCount => all.map((o) => o.shopId).whereType<int>().toSet().length;
}
