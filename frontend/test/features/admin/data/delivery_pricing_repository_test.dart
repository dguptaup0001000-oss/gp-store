import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/delivery_pricing_repository.dart';
import 'package:gpstore/features/admin/domain/delivery_pricing_models.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  Map<String, dynamic> sampleSettings() => {
        'id': 1,
        'distanceTier1Charge': 5,
        'distanceTier1MaxKm': 1,
        'distanceTier2Charge': 10,
        'distanceTier2MaxKm': 2,
        'additionalKmCharge': 5,
        'freeWeightKg': 10,
        'additionalWeightPerKg': 2,
        'maximumWeightSurcharge': 20,
        'freeDeliveryMultiplier': 3,
        'roadDistanceFactor': 1,
        'assumedWeightPerItemKg': 0,
        'updatedAt': '2026-08-24T07:22:00',
        'updatedBy': 'admin:1',
      };

  const edited = DeliveryPricingSettings(
    id: 1,
    distanceTier1Charge: 6,
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

  group('DeliveryPricingRepository.getSettings', () {
    test('GETs /api/admin/delivery-pricing/settings and parses the row', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/admin/delivery-pricing/settings', (_) => FakeResponse(sampleSettings()));

      final repository = DeliveryPricingRepository(apiClient: buildTestApiClient(adapter));
      final settings = await repository.getSettings();

      expect(settings.distanceTier1Charge, 5);
      expect(settings.freeDeliveryMultiplier, 3);
      expect(settings.updatedBy, 'admin:1');
    });
  });

  group('DeliveryPricingRepository.saveSettings', () {
    test('PUTs the editor fields to /api/admin/delivery-pricing/settings', () async {
      final adapter = FakeHttpClientAdapter();
      late Map<String, dynamic> captured;

      adapter.on('PUT', '/api/admin/delivery-pricing/settings', (options) {
        captured = Map<String, dynamic>.from(options.data as Map);
        return FakeResponse({...sampleSettings(), 'distanceTier1Charge': 6});
      });

      final repository = DeliveryPricingRepository(apiClient: buildTestApiClient(adapter));
      final saved = await repository.saveSettings(edited);

      expect(captured['distanceTier1Charge'], 6);
      expect(captured['freeDeliveryMultiplier'], 3);
      expect(saved.distanceTier1Charge, 6);
    });
  });

  group('DeliveryPricingRepository.getOrderBreakdown', () {
    test('GETs the stored breakdown for that order id and does not invent a quote', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/admin/delivery-pricing/orders/41', (_) => const FakeResponse({
            'orderId': 41,
            'orderNumber': 'ORD-41',
            'distanceCharge': 10,
            'finalDeliveryCharge': 0,
            'freeDelivery': true,
            'pricedByCurrentSystem': true,
            'notes': 'ok',
          }));

      final repository = DeliveryPricingRepository(apiClient: buildTestApiClient(adapter));
      final breakdown = await repository.getOrderBreakdown(41);

      expect(breakdown.orderId, 41);
      expect(breakdown.pricedByCurrentSystem, isTrue);
      expect(breakdown.finalDeliveryCharge, 0);
      expect(breakdown.notes, 'ok');
    });
  });
}
