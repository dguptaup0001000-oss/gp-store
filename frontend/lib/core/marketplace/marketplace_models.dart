import 'package:freezed_annotation/freezed_annotation.dart';

part 'marketplace_models.freezed.dart';
part 'marketplace_models.g.dart';

/// One storefront, exactly as `MarketplaceController.StorefrontView` sends it.
///
/// DELIBERATELY THIN, and the thinness is the backend's decision rather than
/// this app's. A browsing customer is not shown the merchant's legal name,
/// their contact details or why a shop is suspended - a shop the marketplace
/// does not show is simply absent. Nothing here reconstructs any of that.
///
/// EVERY FIELD BELOW IS ONE THE SERVER ALREADY SENDS. The app used to parse
/// six of them and drop the rest on the floor, which is why a customer could
/// not see that a shop was shut, paused, unverified, or out of range. Adding
/// a field here is free; computing one would not be, and none is computed.
@freezed
class Storefront with _$Storefront {
  const factory Storefront({
    required int shopId,
    String? code,
    String? displayName,
    double? latitude,
    double? longitude,
    String? logoUrl,

    /// What GP-STORE has checked about this merchant, and nothing more.
    ///
    /// The level is the enum name; the badge is the server's own wording for
    /// it. The app shows the badge rather than mapping the enum to English,
    /// so a new level added on the server does not render as a blank chip.
    String? verificationLevel,
    String? verificationBadge,
    double? maxDeliveryRadiusKm,

    /// How far this shop is from the point that was asked about.
    ///
    /// Null when the question had no point in it - opening one storefront by
    /// id rather than asking which shops serve an address. Null is "not
    /// asked", never "zero".
    double? distanceKm,

    /// Whether THIS SHOP's own radius reaches the pin.
    ///
    /// SEPARATE FROM THE SEARCH RADIUS, and that separation is the whole
    /// point of "search farther": widening the search shows the customer
    /// shops that exist, it does not widen what any shop has promised. A
    /// storefront with deliversHere false is real, visible, and cannot
    /// deliver to this address.
    bool? deliversHere,

    /// Browsing is never closed; ordering can be.
    ///
    /// `openNow` is whether the shelves can be looked at, `acceptingOrders`
    /// whether a basket will be taken. A shut kirana is still worth browsing,
    /// which is why these are two fields and not one.
    @Default(true) bool openNow,
    @Default(true) bool acceptingOrders,
    @Default(false) bool closedToday,
    String? closureReason,

    /// The difference between "back at four" and "closed".
    String? pausedUntil,
    String? nextDeliveryDate,
    String? supportPhone,
    String? timeZone,

    /// What customers have said, on the list and not only on the shop page.
    ///
    /// NULL AVERAGE WITH A ZERO COUNT MEANS UNRATED, and every screen must
    /// draw it that way. A new kirana nobody has rated yet is not a nought
    /// out of five, and an app that says so is libelling a real merchant. The
    /// server sends null rather than 0.0 for exactly this reason.
    double? ratingAverage,
    @Default(0) int ratingCount,
  }) = _Storefront;

  factory Storefront.fromJson(Map<String, dynamic> json) => _$StorefrontFromJson(json);
}

/// A promise this shop makes, in its own words.
@freezed
class ShopPolicy with _$ShopPolicy {
  const factory ShopPolicy({
    required String kind,
    String? body,
  }) = _ShopPolicy;

  factory ShopPolicy.fromJson(Map<String, dynamic> json) => _$ShopPolicyFromJson(json);
}

/// One reason customers keep giving, and how often.
@freezed
class RatingReasonCount with _$RatingReasonCount {
  const factory RatingReasonCount({
    String? reason,
    @Default(0) int count,
    @Default(false) bool praise,
  }) = _RatingReasonCount;

  factory RatingReasonCount.fromJson(Map<String, dynamic> json) =>
      _$RatingReasonCountFromJson(json);
}

/// What customers have actually said about a shop.
///
/// ONE NUMBER IS NOT ENOUGH, which is why the server sends four. A shop that
/// was good two years ago and is poor now has a flattering lifetime average
/// and a truthful recent one; `verifiedCount` says how many of the ratings
/// came from someone who actually bought something.
///
/// ZERO IS NOT THREE. An unrated shop has average 0 and count 0, and the
/// screen must say "not rated yet" rather than draw half the stars.
@freezed
class ShopRatingSummary with _$ShopRatingSummary {
  // Freezed requires this before a class may carry a getter of its own.
  const ShopRatingSummary._();

  const factory ShopRatingSummary({
    @Default(0) double average,
    @Default(0) int count,
    @Default(0) double recentAverage,
    @Default(0) int recentCount,
    @Default(0) int recentDays,
    @Default(0) int verifiedCount,
    @Default([]) List<RatingReasonCount> topReasons,
  }) = _ShopRatingSummary;

  factory ShopRatingSummary.fromJson(Map<String, dynamic> json) =>
      _$ShopRatingSummaryFromJson(json);

  /// Whether anybody has rated this shop at all.
  bool get hasRating => count > 0;
}

/// One storefront as a customer deciding whether to buy from it sees it.
@freezed
class StorefrontDetail with _$StorefrontDetail {
  const factory StorefrontDetail({
    required Storefront shop,

    /// §10's EARNED badge, recomputed from this shop's own trading record
    /// every time it is asked. There is no column behind it, which is what
    /// makes it unpurchasable rather than merely expensive.
    @Default(false) bool trusted,
    @Default([]) List<ShopPolicy> policies,
    String? businessName,
    ShopRatingSummary? rating,
  }) = _StorefrontDetail;

  factory StorefrontDetail.fromJson(Map<String, dynamic> json) =>
      _$StorefrontDetailFromJson(json);
}

/// A page of discovery: what was searched, what came back, and how much
/// farther there is to look.
///
/// THE LADDER IS THE SERVER'S. The app does not invent "try 10 km next" -
/// two clients would then disagree about what farther means and neither
/// would be the marketplace's answer. `nextRadiusKm` null means there is
/// nowhere farther to go, which is how the button knows to stop offering.
///
/// `message` IS THE SERVER'S SENTENCE, not a template this app fills in.
/// "No shops within 8 km. Showing results within 20 km." is composed where
/// the widening decision was made, so the words and the behaviour cannot
/// drift apart.
@freezed
class DiscoveryPage with _$DiscoveryPage {
  const DiscoveryPage._();

  const factory DiscoveryPage({
    double? radiusKm,
    double? nextRadiusKm,
    double? maxRadiusKm,
    @Default([]) List<Storefront> shops,
    double? askedRadiusKm,
    @Default(false) bool widened,
    String? message,
  }) = _DiscoveryPage;

  factory DiscoveryPage.fromJson(Map<String, dynamic> json) =>
      _$DiscoveryPageFromJson(json);

  /// Whether there is a farther rung to offer.
  bool get canSearchFarther => nextRadiusKm != null;
}

/// A category a customer at this pin can actually buy from, and how many
/// nearby shops sell it.
///
/// NOT THE CATALOGUE. `/api/categories` lists every category the platform has
/// ever defined, which is the right answer for a Super Admin and the wrong one
/// for a customer in a town with four kiranas and a chemist: it offers twenty
/// doors, eighteen of which open onto "no shops found". This is the answer to
/// the customer's question instead - what can I buy here - and `shopCount` is
/// why the list can be ordered by usefulness rather than by row id.
///
/// THE ID IS THE CATALOGUE'S. Nothing here is a second copy of a category; the
/// Super Admin renames one in one place and every screen follows.
@freezed
class MarketCategory with _$MarketCategory {
  const factory MarketCategory({
    required int categoryId,
    String? name,
    String? imageUrl,

    /// How many shops serving this pin stock it. Never zero - a category no
    /// shop stocks is absent from the list rather than present with a zero,
    /// because a zero is a door that opens onto nothing.
    @Default(0) int shopCount,
  }) = _MarketCategory;

  factory MarketCategory.fromJson(Map<String, dynamic> json) =>
      _$MarketCategoryFromJson(json);
}

/// One shop's answer to "what would this item cost me, from you?"
///
/// `finalPayable` IS THE FIELD THAT MATTERS: price - discount + delivery.
/// Comparing shelf prices alone gets the answer backwards whenever delivery
/// differs, which is most of the time.
///
/// `deliveryChargeKnown` EXISTS BECAUSE ZERO IS A LIE. A shop whose charge
/// cannot be worked out must not be drawn as free delivery and win the
/// comparison on a number nobody quoted.
@freezed
class ShopOffer with _$ShopOffer {
  const ShopOffer._();

  const factory ShopOffer({
    required int shopId,
    String? shopName,
    String? logoUrl,
    double? distanceKm,
    @Default(false) bool deliversHere,
    @Default(false) bool openNow,
    @Default(false) bool acceptingOrders,
    String? verificationLevel,
    String? verificationBadge,
    @Default(false) bool trusted,
    @Default(0) double ratingAverage,
    @Default(0) int ratingCount,
    int? productId,
    int? variantId,
    @Default(false) bool listed,
    @Default(false) bool inStock,

    /// Null when this shop does not price the item - out of stock, or not
    /// listed at all. Part 2 §10: an empty shelf shows no price.
    double? sellingPrice,
    double? mrp,
    double? discount,
    double? deliveryCharge,
    @Default(false) bool deliveryChargeKnown,
    double? finalPayable,
    String? estimatedDeliveryDate,
  }) = _ShopOffer;

  factory ShopOffer.fromJson(Map<String, dynamic> json) => _$ShopOfferFromJson(json);

  /// Whether a customer could actually put this in a basket right now.
  ///
  /// The same five conditions the server applies in `ShopOffer.isBuyableNow`.
  /// Kept in step deliberately: the button drawn from this is a courtesy, and
  /// the server refuses the add regardless of what the app decided.
  bool get isBuyableNow =>
      listed && inStock && deliversHere && acceptingOrders && finalPayable != null;
}

/// Every shop's price for one item, ordered by Best Deal.
///
/// `fartherSellerQualifies` IS §7, ANSWERED BY THE SERVER. The 25% rule is a
/// business rule about final customer cost, and the app must not re-derive it
/// from the two numbers - a rounding difference in Dart would show a customer
/// a recommendation the marketplace did not make.
@freezed
class ShopComparison with _$ShopComparison {
  const factory ShopComparison({
    int? variantId,
    @Default([]) List<ShopOffer> offers,
    int? nearestShopId,
    double? nearestFinalPayable,
    int? cheapestShopId,
    double? cheapestFinalPayable,
    double? priceGapMultiplier,
    @Default(false) bool fartherSellerQualifies,
    @Default(0) double saving,
  }) = _ShopComparison;

  factory ShopComparison.fromJson(Map<String, dynamic> json) =>
      _$ShopComparisonFromJson(json);
}

/// The same offers in the customer's own order.
///
/// THE OTHER SHOPS ARE STILL IN THE LIST. Preferred mode REORDERS and never
/// filters, so a customer can always see, compare and buy elsewhere.
/// `hasPreference` is the honest label for an empty list: this is distance
/// order, not a preference they forgot they set.
@freezed
class PreferredFirstOffers with _$PreferredFirstOffers {
  const factory PreferredFirstOffers({
    int? variantId,
    int? categoryId,
    @Default([]) List<int> preferredShopIds,
    @Default([]) List<ShopOffer> offers,
    @Default(false) bool hasPreference,
  }) = _PreferredFirstOffers;

  factory PreferredFirstOffers.fromJson(Map<String, dynamic> json) =>
      _$PreferredFirstOffersFromJson(json);
}

/// A customer's chosen shops for one category.
@freezed
class PreferredShopChoice with _$PreferredShopChoice {
  const factory PreferredShopChoice({
    int? categoryId,
    @Default(2) int maxPerCategory,
    @Default([]) List<int> shopIds,
  }) = _PreferredShopChoice;

  factory PreferredShopChoice.fromJson(Map<String, dynamic> json) =>
      _$PreferredShopChoiceFromJson(json);
}

/// One line of a basket, inside one shop's section.
@freezed
class BasketLine with _$BasketLine {
  const factory BasketLine({
    int? cartItemId,
    int? variantId,
    int? productId,
    String? productName,
    @Default(0) int quantity,
    double? unitPrice,
    double? lineTotal,
  }) = _BasketLine;

  factory BasketLine.fromJson(Map<String, dynamic> json) => _$BasketLineFromJson(json);
}

/// One shop's part of the basket, priced by that shop.
///
/// `deliveryKnown` FALSE MEANS THE CHARGE IS NOT ZERO, it means nobody has
/// quoted it - no address, or an address outside this shop's circle. Drawing
/// it as free would understate what the customer is about to pay.
@freezed
class BasketShopSection with _$BasketShopSection {
  const factory BasketShopSection({
    required int shopId,
    String? shopName,
    String? logoUrl,
    @Default([]) List<BasketLine> lines,
    @Default(0) double subtotal,
    @Default(0) double discount,
    @Default(0) double deliveryCharge,
    @Default(false) bool deliveryKnown,
    @Default(0) double shopTotal,
    @Default([]) List<String> notes,
  }) = _BasketShopSection;

  factory BasketShopSection.fromJson(Map<String, dynamic> json) =>
      _$BasketShopSectionFromJson(json);
}

/// The basket drawn as the several purchases it actually is.
///
/// `informationalCombinedTotal` IS NOT A BILL. Each shop is paid separately
/// and billed separately; the combined figure exists so a customer can see
/// what the trip costs them in total, and the screen must label it as such.
/// `isSinglePayment` is false the moment a second shop is in the basket.
@freezed
class BasketByShop with _$BasketByShop {
  const BasketByShop._();

  const factory BasketByShop({
    @Default([]) List<BasketShopSection> shops,
    @Default(0) double informationalCombinedTotal,
    @Default(true) bool isSinglePayment,
    @Default(false) bool deliveryFullyKnown,
    String? note,
  }) = _BasketByShop;

  factory BasketByShop.fromJson(Map<String, dynamic> json) =>
      _$BasketByShopFromJson(json);

  /// Whether this basket genuinely spans shops.
  bool get spansShops => shops.length > 1;
}

/// Whether this deployment is a marketplace at all.
///
/// ASKED, NOT INFERRED. The app must not decide it is single-shop because one
/// shop came back in range - one shop nearby is not the same fact as one shop
/// existing, and getting that wrong means a customer in a quiet area never
/// sees that other shops exist at all. `/api/marketplace/mode` answers it
/// directly for exactly this reason.
@freezed
class MarketplaceMode with _$MarketplaceMode {
  const factory MarketplaceMode({
    required String mode,
    required bool multiShop,
  }) = _MarketplaceMode;

  factory MarketplaceMode.fromJson(Map<String, dynamic> json) => _$MarketplaceModeFromJson(json);

  /// What a deployment that has not answered yet is assumed to be.
  ///
  /// SINGLE SHOP, deliberately. It is the current production shape, and
  /// guessing "marketplace" would draw a shop switcher over a deployment that
  /// has one shop - a screen the customer cannot use and did not ask for.
  static const MarketplaceMode singleShop =
      MarketplaceMode(mode: 'SINGLE_SHOP', multiShop: false);
}
