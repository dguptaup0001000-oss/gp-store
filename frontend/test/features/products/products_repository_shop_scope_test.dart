import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/data/products_repository.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  test('marketplace detail request carries the seller shop from its feed card',
      () async {
    final adapter = FakeHttpClientAdapter();
    String? requestedShop;
    adapter.on('GET', '/api/products/52', (options) {
      requestedShop = options.headers['X-Shop-Id']?.toString();
      return const FakeResponse({'id': 52, 'name': 'Listing in the selected shop'});
    });

    final repository = ProductsRepository(apiClient: buildTestApiClient(adapter));
    final product = await repository.fetchProductDetail(52, shopId: 9);

    expect(requestedShop, '9');
    expect(product.id, 52);
  });
}
