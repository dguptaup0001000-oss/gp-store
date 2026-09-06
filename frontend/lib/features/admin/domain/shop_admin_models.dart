import 'package:freezed_annotation/freezed_annotation.dart';

part 'shop_admin_models.freezed.dart';
part 'shop_admin_models.g.dart';

/// The shop this staff account is acting for.
///
/// NO SHOP ID IS EVER SENT TO FETCH THIS. `/api/shop/profile` answers about
/// the shop the caller's credential resolved to, which is why there is no
/// route shaped `/api/shops/{id}/profile` anywhere - putting an id in front
/// of a caller is what would need guarding, so it was never put there.
@freezed
class ShopProfile with _$ShopProfile {
  const factory ShopProfile({
    required int id,
    String? code,
    String? displayName,
    String? status,
    String? statusReason,
    double? latitude,
    double? longitude,
    double? maxDeliveryRadiusKm,
    String? timeZone,
    String? supportPhone,
    String? supportEmail,
    String? supportWhatsapp,
  }) = _ShopProfile;

  factory ShopProfile.fromJson(Map<String, dynamic> json) => _$ShopProfileFromJson(json);
}

/// One thing standing between this shop and its first order.
@freezed
class ReadinessStep with _$ReadinessStep {
  const factory ReadinessStep({
    required String name,
    @Default(false) bool done,

    /// Whether this stops orders outright, or only makes them worse.
    ///
    /// THE DISTINCTION IS THE POINT. An order still goes out with no
    /// territory drawn and no rider hired - it goes to whoever is least
    /// loaded rather than to somebody who knows the streets. A screen that
    /// painted both as "blocked" would train a shopkeeper to ignore the list.
    @Default(false) bool blocking,
    String? detail,
  }) = _ReadinessStep;

  factory ReadinessStep.fromJson(Map<String, dynamic> json) => _$ReadinessStepFromJson(json);
}

/// What a shopkeeper is told when nothing is selling.
///
/// The customer's version of this answer is one sentence with no detail in it
/// - "This shop is not currently taking orders" - because the platform's
/// reasons are between the platform and the merchant. This is the other half,
/// and it is only ever shown to the shop.
@freezed
class ShopReadiness with _$ShopReadiness {
  const factory ShopReadiness({
    int? shopId,
    String? displayName,
    @Default(false) bool canTakeOrders,
    @Default([]) List<ReadinessStep> steps,
  }) = _ShopReadiness;

  // Required by freezed for the two getters below.
  const ShopReadiness._();

  factory ShopReadiness.fromJson(Map<String, dynamic> json) => _$ShopReadinessFromJson(json);

  List<ReadinessStep> get outstandingBlockers =>
      steps.where((step) => step.blocking && !step.done).toList(growable: false);

  List<ReadinessStep> get outstandingAdvice =>
      steps.where((step) => !step.blocking && !step.done).toList(growable: false);
}

/// What this shop took, and in what form it arrived.
///
/// THERE IS NO COMMISSION FIELD, and its absence is deliberate rather than an
/// oversight. Under decision W1 each merchant collects directly, so there is
/// nothing for the platform to settle; and the commission model itself (W2)
/// has not been decided. A zero-valued fee shown beside real money would look
/// like a decision that has been made.
@freezed
class ShopEarnings with _$ShopEarnings {
  const factory ShopEarnings({
    @Default(0) int periodDays,
    String? from,
    String? to,
    @Default(0) double grossSales,
    @Default(0) double refunds,
    @Default(0) double netSales,
    @Default(0) double collectedOnline,

    /// Notes a rider took at the door, and is currently carrying.
    @Default(0) double collectedCash,
    @Default(0) double collectedCodUpi,

    /// Ordered COD that has not been handed over yet.
    @Default(0) double awaitingCollection,
    @Default(0) int orderCount,
    @Default(0) int cancelledCount,
  }) = _ShopEarnings;

  factory ShopEarnings.fromJson(Map<String, dynamic> json) => _$ShopEarningsFromJson(json);
}
