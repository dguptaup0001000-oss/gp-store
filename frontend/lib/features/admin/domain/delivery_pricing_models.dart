import 'package:freezed_annotation/freezed_annotation.dart';

part 'delivery_pricing_models.freezed.dart';
part 'delivery_pricing_models.g.dart';

/// One shop-wide row of delivery price rules.
///
/// Mirrors backend [DeliveryPricingSettings] JSON (camelCase). The client
/// never computes a quote from these fields — checkout pricing stays on
/// the server. This model is only for the admin editor and for sending a
/// PUT of the same field names the GET returned.
@freezed
class DeliveryPricingSettings with _$DeliveryPricingSettings {
  const factory DeliveryPricingSettings({
    int? id,
    required double distanceTier1Charge,
    required double distanceTier1MaxKm,
    required double distanceTier2Charge,
    required double distanceTier2MaxKm,
    required double additionalKmCharge,
    required double freeWeightKg,
    required double additionalWeightPerKg,
    required double maximumWeightSurcharge,
    required double freeDeliveryMultiplier,
    required double roadDistanceFactor,
    required double assumedWeightPerItemKg,
    String? updatedAt,
    String? updatedBy,
  }) = _DeliveryPricingSettings;

  factory DeliveryPricingSettings.fromJson(Map<String, dynamic> json) =>
      _$DeliveryPricingSettingsFromJson(json);
}

/// Stored breakdown for one order from GET /api/admin/delivery-pricing/orders/{id}.
///
/// Values are what was written at place-order time. The screen must render
/// them as-is — re-running the calculator here would show a number the
/// customer was never charged if settings have changed since.
@freezed
class DeliveryOrderBreakdown with _$DeliveryOrderBreakdown {
  const factory DeliveryOrderBreakdown({
    int? orderId,
    String? orderNumber,
    double? orderValue,
    double? availableProfit,
    double? distanceKm,
    double? totalWeightKg,
    double? distanceCharge,
    double? weightCharge,
    double? normalDeliveryCharge,
    double? freeDeliveryRequiredProfit,
    @Default(false) bool freeDelivery,
    double? subsidy,
    double? finalDeliveryCharge,
    String? notes,
    @Default(false) bool pricedByCurrentSystem,
  }) = _DeliveryOrderBreakdown;

  factory DeliveryOrderBreakdown.fromJson(Map<String, dynamic> json) =>
      _$DeliveryOrderBreakdownFromJson(json);
}
