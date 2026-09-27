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
}
