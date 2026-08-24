import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/delivery_pricing_models.dart';

void main() {
  group('DeliveryPricingSettings.fromJson', () {
    test('reads camelCase fields, including whole numbers sent as ints', () {
      final settings = DeliveryPricingSettings.fromJson({
        'id': 1,
        'distanceTier1Charge': 5,
        'distanceTier1MaxKm': 1,
        'distanceTier2Charge': 10.0,
        'distanceTier2MaxKm': 2.0,
        'additionalKmCharge': 5,
        'freeWeightKg': 10,
        'additionalWeightPerKg': 2,
        'maximumWeightSurcharge': 20,
        'freeDeliveryMultiplier': 3,
        'roadDistanceFactor': 1,
        'assumedWeightPerItemKg': 0,
        'updatedAt': '2026-08-24T07:22:00',
        'updatedBy': 'admin:9',
      });

      expect(settings.id, 1);
      expect(settings.distanceTier1Charge, 5);
      expect(settings.distanceTier1MaxKm, 1);
      expect(settings.distanceTier2Charge, 10);
      expect(settings.freeDeliveryMultiplier, 3);
      expect(settings.assumedWeightPerItemKg, 0);
      expect(settings.updatedBy, 'admin:9');
    });

    test('PUT payload keeps the same field names the GET uses', () {
      const settings = DeliveryPricingSettings(
        id: 1,
        distanceTier1Charge: 5,
        distanceTier1MaxKm: 1,
        distanceTier2Charge: 10,
        distanceTier2MaxKm: 2,
        additionalKmCharge: 5,
        freeWeightKg: 10,
        additionalWeightPerKg: 2,
        maximumWeightSurcharge: 20,
        freeDeliveryMultiplier: 3,
        roadDistanceFactor: 1,
        assumedWeightPerItemKg: 0,
      );

      expect(settings.toJson(), containsPair('distanceTier1Charge', 5.0));
      expect(settings.toJson(), containsPair('freeDeliveryMultiplier', 3.0));
      expect(settings.toJson(), containsPair('assumedWeightPerItemKg', 0.0));
    });
  });

  group('DeliveryOrderBreakdown.fromJson', () {
    test('reads a stored current-system breakdown without inventing totals', () {
      final breakdown = DeliveryOrderBreakdown.fromJson({
        'orderId': 41,
        'orderNumber': 'ORD-41',
        'orderValue': 499,
        'availableProfit': 90,
        'distanceKm': 1.4,
        'totalWeightKg': 3.25,
        'distanceCharge': 10,
        'weightCharge': 0,
        'normalDeliveryCharge': 10,
        'freeDeliveryRequiredProfit': 30,
        'freeDelivery': true,
        'subsidy': 10,
        'finalDeliveryCharge': 0,
        'notes': 'used assumed weight on 1 item',
        'pricedByCurrentSystem': true,
      });

      expect(breakdown.orderId, 41);
      expect(breakdown.pricedByCurrentSystem, isTrue);
      expect(breakdown.freeDelivery, isTrue);
      expect(breakdown.finalDeliveryCharge, 0);
      expect(breakdown.notes, 'used assumed weight on 1 item');
      expect(breakdown.distanceKm, 1.4);
    });

    test('treats a pre-V21 order as not priced by the current system', () {
      final breakdown = DeliveryOrderBreakdown.fromJson({
        'orderId': 2,
        'orderNumber': 'ORD-2',
        'pricedByCurrentSystem': false,
        'normalDeliveryCharge': null,
        'finalDeliveryCharge': 15,
      });

      expect(breakdown.pricedByCurrentSystem, isFalse);
      expect(breakdown.normalDeliveryCharge, isNull);
      expect(breakdown.finalDeliveryCharge, 15);
      expect(breakdown.freeDelivery, isFalse);
    });
  });
}
