import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/demand/data/demand_repository.dart';

import '../../support/test_api_client.dart';

void main() {
  setUp(setUpFakeSecureStorage);

  test('my requests preserve merchant responses and readiness details',
      () async {
    final adapter = FakeHttpClientAdapter();
    adapter.on('GET', '/api/demand-requests/mine', (options) {
      expect(options.queryParameters, {'page': 0, 'size': 20});
      return const FakeResponse([
        {
          'id': 17,
          'description': 'tractor brake pad',
          'quantity': 2,
          'status': 'OPEN',
          'expiresAt': '2026-09-30T10:00:00',
          'responses': [
            {
              'id': 91,
              'shopId': 12,
              'shopName': 'Local Tractor Parts',
              'status': 'AVAILABLE',
              'price': 850,
              'quantity': 2,
              'readyMinutes': 45,
              'commerceMode': 'VISIT_TO_BUY',
              'note': 'Bring the old part for matching',
            }
          ],
        }
      ]);
    });

    final repository =
        DemandRepository(apiClient: buildTestApiClient(adapter));
    final requests = await repository.mine();

    expect(requests, hasLength(1));
    expect(requests.single.description, 'tractor brake pad');
    expect(requests.single.responses.single.shopName, 'Local Tractor Parts');
    expect(requests.single.responses.single.readyMinutes, 45);
    expect(requests.single.responses.single.commerceMode, 'VISIT_TO_BUY');
  });

  test('close and cancel use separate lifecycle endpoints', () async {
    final adapter = FakeHttpClientAdapter();
    Map<String, dynamic> response(String status) => {
          'id': 17,
          'description': 'tractor brake pad',
          'quantity': 1,
          'status': status,
          'responses': <dynamic>[],
        };
    adapter.on(
      'POST',
      '/api/demand-requests/17/close',
      (_) => FakeResponse(response('CLOSED')),
    );
    adapter.on(
      'POST',
      '/api/demand-requests/17/cancel',
      (_) => FakeResponse(response('CANCELLED')),
    );

    final repository =
        DemandRepository(apiClient: buildTestApiClient(adapter));

    expect((await repository.close(17)).status, 'CLOSED');
    expect((await repository.cancel(17)).status, 'CANCELLED');
  });

  test('create sends optional routing and expiry constraints', () async {
    final adapter = FakeHttpClientAdapter();
    final requiredBy = DateTime(2026, 10, 2, 23, 59);
    adapter.on('POST', '/api/demand-requests', (options) {
      expect(options.data, {
        'description': 'tractor brake pad',
        'quantity': 2,
        'latitude': 22.3,
        'longitude': 78.4,
        'radiusKm': 12.0,
        'budget': 1000.0,
        'categoryId': 44,
        'requiredBy': requiredBy.toIso8601String(),
        'preferredMode': 'VISIT_TO_BUY',
      });
      return const FakeResponse({
        'id': 18,
        'description': 'tractor brake pad',
        'quantity': 2,
        'status': 'OPEN',
        'responses': <dynamic>[],
      });
    });

    final repository =
        DemandRepository(apiClient: buildTestApiClient(adapter));
    final request = await repository.create(
      description: 'tractor brake pad',
      latitude: 22.3,
      longitude: 78.4,
      quantity: 2,
      radiusKm: 12,
      budget: 1000,
      categoryId: 44,
      requiredBy: requiredBy,
      preferredMode: 'VISIT_TO_BUY',
    );

    expect(request.id, 18);
  });

  test('report response can block future demand from that shop', () async {
    final adapter = FakeHttpClientAdapter();
    adapter.on('POST', '/api/demand-responses/91/report', (options) {
      expect(options.data, {
        'reason': 'MISLEADING',
        'detail': 'Price changed after response',
        'blockShop': true,
      });
      return const FakeResponse({
        'id': 3,
        'responseId': 91,
        'reason': 'MISLEADING',
        'status': 'OPEN',
        'shopBlocked': true,
      });
    });

    final repository =
        DemandRepository(apiClient: buildTestApiClient(adapter));
    await repository.reportResponse(
      91,
      reason: 'MISLEADING',
      detail: 'Price changed after response',
    );
  });
}
