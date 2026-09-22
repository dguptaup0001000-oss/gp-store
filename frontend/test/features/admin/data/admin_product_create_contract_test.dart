import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/admin_products_repository.dart';
import 'package:gpstore/features/admin/domain/selling_mode.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  test('Visit to Buy is nested in firstVariant exactly as ProductCreateRequest reads it',
      () async {
    final adapter = FakeHttpClientAdapter();
    Map<String, dynamic>? body;
    adapter.on('POST', '/api/shop/products', (options) {
      body = Map<String, dynamic>.from(options.data as Map);
      return const FakeResponse({
        'id': 50,
        'name': 'Motorola Edge 50 Pro',
        'active': true,
        'variants': [],
      });
    });

    final repository = AdminProductsRepository(
        apiClient: buildTestApiClient(adapter));
    await repository.createProduct(
      name: 'Motorola Edge 50 Pro',
      brand: 'Motorola',
      categoryId: 7,
      firstVariant: const AdminFirstVariant(
        label: '12 GB + 256 GB',
        sellingPrice: 29999,
        mrp: 35999,
        stock: 4,
        selling: SellingSetup(
          selling: SellingMode.visitToBuy,
          price: PriceMode.startingFrom,
          stock: OfflineStock.available,
        ),
      ),
    );

    expect(body?['categoryId'], 7);
    final first = Map<String, dynamic>.from(body?['firstVariant'] as Map);
    expect(first['commerceMode'], 'VISIT_TO_BUY');
    expect(first['priceMode'], 'STARTING_FROM');
    expect(first['offlineAvailability'], 'AVAILABLE');
    expect(first['sellingPrice'], 29999);
  });
}
