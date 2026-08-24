import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/territory_repository.dart';
import 'package:gpstore/features/admin/domain/territory_models.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('TerritoryRepository.getHealth', () {
    test('GETs /api/admin/territory/health', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on(
        'GET',
        '/api/admin/territory/health',
        (_) => const FakeResponse({
          'zones': 0,
          'expectedZones': 8,
          'subzones': 0,
          'expectedSubzones': 26,
          'subzonesWithBoundary': 0,
          'subzonesWithPrimaryPartner': 0,
          'problems': ['Expected 8 main zones, found 0.'],
        }),
      );

      final health = await TerritoryRepository(apiClient: buildTestApiClient(adapter)).getHealth();
      expect(health.expectedZones, 8);
      expect(health.hasProblems, isTrue);
    });
  });

  group('TerritoryRepository.saveZone', () {
    test('POSTs /api/admin/territory/zones', () async {
      final adapter = FakeHttpClientAdapter();
      late Map<String, dynamic> captured;
      adapter.on('POST', '/api/admin/territory/zones', (options) {
        captured = Map<String, dynamic>.from(options.data as Map);
        return FakeResponse({...captured, 'id': 1});
      });

      final saved = await TerritoryRepository(apiClient: buildTestApiClient(adapter)).saveZone(
        const TerritoryZone(code: 'Z1', name: 'Station'),
      );
      expect(captured['code'], 'Z1');
      expect(saved.id, 1);
    });
  });

  group('TerritoryRepository.saveSubzone', () {
    test('POSTs under the zone and keeps an existing boundary string', () async {
      final adapter = FakeHttpClientAdapter();
      late Map<String, dynamic> captured;
      adapter.on('POST', '/api/admin/territory/zones/7/subzones', (options) {
        captured = Map<String, dynamic>.from(options.data as Map);
        return FakeResponse({...captured, 'id': 4, 'code': 'Z7B', 'name': 'Canal Colony'});
      });

      await TerritoryRepository(apiClient: buildTestApiClient(adapter)).saveSubzone(
        zoneId: 7,
        subzone: const TerritorySubzone(
          id: 4,
          code: 'Z7B',
          name: 'Canal Colony',
          boundary: '[[28.61,77.20],[28.62,77.20],[28.62,77.21]]',
        ),
      );

      expect(captured['boundary'], '[[28.61,77.20],[28.62,77.20],[28.62,77.21]]');
      expect(captured.containsKey('zone'), isFalse);
      expect(captured.containsKey('primaryPartner'), isFalse);
    });
  });

  group('TerritoryRepository.setPrimaryPartner', () {
    test('PUTs partnerId to /api/admin/territory/subzones/{id}/primary-partner', () async {
      final adapter = FakeHttpClientAdapter();
      late Map<String, dynamic> captured;
      adapter.on('PUT', '/api/admin/territory/subzones/4/primary-partner', (options) {
        captured = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({'id': 4, 'code': 'Z7B', 'name': 'Canal Colony'});
      });

      await TerritoryRepository(apiClient: buildTestApiClient(adapter))
          .setPrimaryPartner(subzoneId: 4, partnerId: 9);
      expect(captured['partnerId'], 9);
    });
  });

  group('TerritoryRepository.resolvePoint', () {
    test('GETs /api/admin/territory/resolve with latitude and longitude', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/admin/territory/resolve', (options) {
        expect(options.queryParameters['latitude'], 28.61);
        expect(options.queryParameters['longitude'], 77.21);
        return const FakeResponse({
          'subzoneCode': '',
          'matches': <String>[],
          'overlapping': false,
          'mappedTerritories': 0,
        });
      });

      final result = await TerritoryRepository(apiClient: buildTestApiClient(adapter))
          .resolvePoint(latitude: 28.61, longitude: 77.21);
      expect(result.mappedTerritories, 0);
    });
  });
}
