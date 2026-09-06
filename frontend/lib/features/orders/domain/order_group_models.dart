import 'package:freezed_annotation/freezed_annotation.dart';

part 'order_group_models.freezed.dart';
part 'order_group_models.g.dart';

/// One shop's part of a checkout, as `OrderGroupService.ShopOrderView` sends it.
@freezed
class ShopOrderView with _$ShopOrderView {
  const factory ShopOrderView({
    required int orderId,
    String? orderNumber,
    int? shopId,
    String? shopStatus,
    String? paymentStatus,
    double? totalAmount,
    double? deliveryFee,

    /// Whether the shop can still stop this one.
    ///
    /// A HINT FOR A BUTTON, NOT THE RULE - the backend decides again when the
    /// button is pressed. It exists so the screen does not offer an action
    /// that will be refused a second later.
    @Default(false) bool cancellable,
  }) = _ShopOrderView;

  factory ShopOrderView.fromJson(Map<String, dynamic> json) => _$ShopOrderViewFromJson(json);
}

/// A whole checkout: one group, one order per shop.
@freezed
class OrderGroupSummary with _$OrderGroupSummary {
  const factory OrderGroupSummary({
    required int id,
    String? groupNumber,
    double? totalAmount,
    @Default(0) int shopCount,
    String? placedAt,
    @Default([]) List<ShopOrderView> shopOrders,
  }) = _OrderGroupSummary;

  factory OrderGroupSummary.fromJson(Map<String, dynamic> json) =>
      _$OrderGroupSummaryFromJson(json);
}

/// What happened when one shop's order was asked to cancel.
@freezed
class OrderGroupCancelOutcome with _$OrderGroupCancelOutcome {
  const factory OrderGroupCancelOutcome({
    int? orderId,
    int? shopId,
    @Default(false) bool cancelled,

    /// Why not, in the backend's words, when it could not be cancelled.
    String? reason,
  }) = _OrderGroupCancelOutcome;

  factory OrderGroupCancelOutcome.fromJson(Map<String, dynamic> json) =>
      _$OrderGroupCancelOutcomeFromJson(json);
}

/// The result of cancelling a whole checkout.
///
/// PARTIAL SUCCESS IS AN ORDINARY OUTCOME, not an error. One kirana may still
/// be packing while the other's rider is at the door, so the request can and
/// does half-succeed - and the customer needs to be told which half.
@freezed
class OrderGroupCancelResult with _$OrderGroupCancelResult {
  const factory OrderGroupCancelResult({
    int? groupId,
    String? groupNumber,
    @Default([]) List<OrderGroupCancelOutcome> outcomes,
  }) = _OrderGroupCancelResult;

  // Required by freezed for the three getters below.
  const OrderGroupCancelResult._();

  factory OrderGroupCancelResult.fromJson(Map<String, dynamic> json) =>
      _$OrderGroupCancelResultFromJson(json);

  bool get allCancelled =>
      outcomes.isNotEmpty && outcomes.every((outcome) => outcome.cancelled);

  bool get partiallyCancelled =>
      outcomes.any((outcome) => outcome.cancelled) && !allCancelled;

  /// The shops that refused, with the reason each gave.
  List<OrderGroupCancelOutcome> get refused =>
      outcomes.where((outcome) => !outcome.cancelled).toList(growable: false);
}
