import 'package:freezed_annotation/freezed_annotation.dart';

part 'platform_models.freezed.dart';
part 'platform_models.g.dart';

/// A business on the platform, as `PlatformMerchantController.MerchantView`
/// sends it.
///
/// APPLICATION -> PENDING_REVIEW -> APPROVED -> ACTIVE, and the last two are
/// different things: approved means the papers are in order, active means the
/// business is trading. A shop can be fully built and open under a merchant
/// that is only approved, and sell nothing.
@freezed
class MerchantView with _$MerchantView {
  const factory MerchantView({
    required int id,
    String? legalName,
    String? displayName,
    String? contactPhone,
    String? contactEmail,
    String? status,

    /// Why the platform moved them to this status. Shown to the platform and
    /// to the merchant; never to a customer.
    String? statusReason,
    int? ownerCustomerId,
    bool? isDemo,
    bool? active,

    /// M-000001. Derived server-side for reading aloud, never for deciding.
    String? merchantRef,
  }) = _MerchantView;

  // Required by freezed for the getter below: a class that adds anything of
  // its own needs a private constructor for the generated code to extend.
  const MerchantView._();

  factory MerchantView.fromJson(Map<String, dynamic> json) => _$MerchantViewFromJson(json);

  bool get isTrading => status == 'ACTIVE';
}

/// A shop, as the platform sees it.
@freezed
class PlatformShopView with _$PlatformShopView {
  const factory PlatformShopView({
    required int id,
    int? merchantId,
    String? code,
    String? displayName,
    String? status,
    String? statusReason,
    bool? isDemo,
    bool? active,

    /// S-000001, beside the merchant's own shop code rather than instead of it.
    String? shopRef,
    String? merchantRef,
  }) = _PlatformShopView;

  factory PlatformShopView.fromJson(Map<String, dynamic> json) =>
      _$PlatformShopViewFromJson(json);
}

/// One merchant and every shop under it, as `MerchantDetail` sends it.
///
/// ONE CALL, BECAUSE THE QUESTION IS ONE QUESTION. "Deepak Enterprises, three
/// shops, one of them paused" is what the platform owner is looking at.
/// Assembling it from the two list endpoints on this side means a screen that
/// can show one merchant beside another merchant's shops for as long as the
/// second request is in flight - and the shops here were read by merchant id
/// on the server, not filtered from a list this app sent.
@freezed
class PlatformMerchantDetail with _$PlatformMerchantDetail {
  const factory PlatformMerchantDetail({
    required MerchantView merchant,
    @Default([]) List<PlatformShopView> shops,

    /// Sent separately from `shops.length` and trusted over it: the server
    /// counted, and a count taken from a list this app truncated for display
    /// would quietly under-report a merchant's storefronts.
    @Default(0) int shopCount,
  }) = _PlatformMerchantDetail;

  factory PlatformMerchantDetail.fromJson(Map<String, dynamic> json) =>
      _$PlatformMerchantDetailFromJson(json);
}

/// One shop's trading, in the marketplace roll-up.
@freezed
class MarketShopLine with _$MarketShopLine {
  const factory MarketShopLine({
    int? shopId,
    @Default(0) int orderCount,
    @Default(0) int cancelledCount,
    @Default(0) double grossSales,
    @Default(0) double refunds,
    @Default(0) double netSales,
    String? lastOrderAt,
  }) = _MarketShopLine;

  factory MarketShopLine.fromJson(Map<String, dynamic> json) => _$MarketShopLineFromJson(json);
}

@freezed
class MarketTotals with _$MarketTotals {
  const factory MarketTotals({
    @Default(0) int orderCount,
    @Default(0) int cancelledCount,
    @Default(0) double grossSales,
    @Default(0) double refunds,
    @Default(0) double netSales,
    @Default(0) int tradingShops,
  }) = _MarketTotals;

  factory MarketTotals.fromJson(Map<String, dynamic> json) => _$MarketTotalsFromJson(json);
}

/// How the marketplace is doing, shop by shop.
///
/// THE ONE PLACE FIGURES FROM DIFFERENT MERCHANTS SIT SIDE BY SIDE, and only
/// a platform administrator may open it. A shop ADMIN - every permission a
/// shop can grant - is refused the route outright, which is why nothing in
/// this app decides who may see it.
@freezed
class MarketOverview with _$MarketOverview {
  const factory MarketOverview({
    @Default(0) int periodDays,
    @Default([]) List<MarketShopLine> shops,
    MarketTotals? totals,
    @Default(0) int shopCount,
    @Default(0) int merchantCount,
  }) = _MarketOverview;

  factory MarketOverview.fromJson(Map<String, dynamic> json) => _$MarketOverviewFromJson(json);
}

/// A staff login the platform just opened, and the only copy of its password.
///
/// NOT PERSISTED, ANYWHERE. This object exists for as long as the dialog
/// showing it does. The server keeps a bcrypt hash and returns the plaintext
/// exactly once, so writing it to storage here - "so the owner can look it
/// up later" - would rebuild the shared-credential problem the one-time
/// password exists to prevent, on the phone instead of the server.
@freezed
class OpenedStaffAccount with _$OpenedStaffAccount {
  const factory OpenedStaffAccount({
    required int customerId,
    String? email,
    String? role,

    /// Shown once. There is no route that returns it again.
    String? oneTimePassword,

    /// The fifteen characters for the first login, also shown once.
    ///
    /// NULL ON A PASSWORD RESET, because a reset replaces the password and
    /// leaves the code alone - and null on a reissue's password half, for the
    /// mirror-image reason. Each call hands back only what it actually minted.
    String? activationCode,
  }) = _OpenedStaffAccount;

  factory OpenedStaffAccount.fromJson(Map<String, dynamic> json) =>
      _$OpenedStaffAccountFromJson(json);
}

/// Everything one call to POST /api/platform/onboard produced.
///
/// The whole point of the endpoint is that these arrive together or not at
/// all - a merchant, its shop, and the one credential to hand over. Five
/// separate calls could leave any subset of them behind.
@freezed
class OnboardedMerchant with _$OnboardedMerchant {
  const factory OnboardedMerchant({
    required int merchantId,
    String? businessName,
    required int shopId,

    /// Derived from the business name when none was given, so the console
    /// shows what it became rather than what was asked for.
    String? shopCode,
    required int ownerCustomerId,
    String? ownerEmail,

    /// Shown once. There is no route that returns it again.
    String? oneTimePassword,

    /// The other half of the first sign-in, and shown once for the same
    /// reason. WITHOUT THIS THE PASSWORD IS NOT ENOUGH - a merchant handed
    /// only the password cannot get in at all - so the two travel together
    /// from the server to the dialog to the person typing them.
    String? activationCode,
  }) = _OnboardedMerchant;

  factory OnboardedMerchant.fromJson(Map<String, dynamic> json) =>
      _$OnboardedMerchantFromJson(json);
}
