import 'package:freezed_annotation/freezed_annotation.dart';

part 'marketplace_models.freezed.dart';
part 'marketplace_models.g.dart';

/// One storefront, exactly as `MarketplaceController.StorefrontView` sends it.
///
/// DELIBERATELY THIN, and the thinness is the backend's decision rather than
/// this app's. A browsing customer is not shown the merchant's legal name,
/// their contact details or why a shop is suspended - a shop the marketplace
/// does not show is simply absent. Nothing here reconstructs any of that.
@freezed
class Storefront with _$Storefront {
  const factory Storefront({
    required int shopId,
    String? code,
    String? displayName,
    double? latitude,
    double? longitude,
    double? maxDeliveryRadiusKm,

    /// How far this shop is from the point that was asked about.
    ///
    /// Null when the question had no point in it - opening one storefront by
    /// id rather than asking which shops serve an address. Null is "not
    /// asked", never "zero".
    double? distanceKm,
    String? supportPhone,
    String? timeZone,
  }) = _Storefront;

  factory Storefront.fromJson(Map<String, dynamic> json) => _$StorefrontFromJson(json);
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
