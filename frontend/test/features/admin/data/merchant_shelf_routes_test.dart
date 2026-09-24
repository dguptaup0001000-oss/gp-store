import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:gpstore/features/admin/data/admin_products_repository.dart';
import 'package:gpstore/features/admin/domain/selling_mode.dart';
import 'package:gpstore/features/admin/domain/variant_attribute.dart';
import 'package:gpstore/features/admin/presentation/admin_providers.dart';
import 'package:gpstore/features/admin/presentation/admin_variant_form_dialog.dart';
import 'package:gpstore/core/api/error_messages.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:dio/dio.dart';

import '../../../support/test_api_client.dart';

/// WHICH ROUTE THE MERCHANT APP ACTUALLY CALLS.
///
/// This is the test that would have caught the bug a real phone found. Saving
/// a variant and adding a category both went to the PLATFORM's catalogue
/// routes - `/api/product-variants/{id}` and `/api/categories` - which are
/// guarded by CatalogDefinitionAuthorization and answer 403 to a shopkeeper
/// the moment a second merchant is trading. The app then rendered that 403 as
/// "Couldn't save variant - please check the values and try again", sending a
/// merchant to look at prices that were never the problem.
///
/// Nothing in the widget tree could catch that: the screen worked, the request
/// was well-formed, and the only thing wrong was the address on the envelope.
/// So the address is what these tests assert.
void main() {
  setUpAll(setUpFakeSecureStorage);

  group('a merchant saves their own shelf, not the platform catalogue', () {
    test('the edit snapshot reloads the authoritative shop commerce mode',
        () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/shop/variants/42', (options) {
        return const FakeResponse({
          'productVariantId': 42,
          'sellingPrice': 30000,
          'mrp': 35000,
          'available': true,
          'commerceMode': 'VISIT_TO_BUY',
          'priceMode': 'STARTING_FROM',
          'priceMax': 42000,
          'offlineAvailability': 'LIMITED_AVAILABILITY',
          'serviceDurationMinutes': null,
          'attributes': [
            {'name': 'RAM', 'value': '8 GB'},
          ],
        });
      });

      final repository =
          AdminProductsRepository(apiClient: buildTestApiClient(adapter));
      final snapshot = await repository.getVariantForEditing(42);

      expect(snapshot.selling, SellingMode.visitToBuy);
      expect(snapshot.price, PriceMode.startingFrom);
      expect(snapshot.stock, OfflineStock.limited);
      expect(snapshot.priceMax, 42000);
      expect(snapshot.attributes.single.value, '8 GB');
    });

    test('the edit snapshot refuses a missing mode instead of assuming online',
        () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/shop/variants/42', (options) {
        return const FakeResponse({
          'productVariantId': 42,
          'sellingPrice': 30000,
          'priceMode': 'EXACT_PRICE',
          'available': true,
          'attributes': [],
        });
      });
      final repository =
          AdminProductsRepository(apiClient: buildTestApiClient(adapter));

      await expectLater(repository.getVariantForEditing(42),
          throwsA(isA<FormatException>()));
    });

    testWidgets(
        'editing a Visit-to-Buy listing reloads and resends its authoritative mode',
        (tester) async {
      tester.view.physicalSize = const Size(1000, 2200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? saved;
      adapter.on('GET', '/api/shop/variants/42', (options) {
        return const FakeResponse({
          'productVariantId': 42,
          'sellingPrice': 30000,
          'mrp': 35000,
          'available': true,
          'commerceMode': 'VISIT_TO_BUY',
          'priceMode': 'STARTING_FROM',
          'priceMax': null,
          'offlineAvailability': 'AVAILABLE',
          'serviceDurationMinutes': null,
          'attributes': [],
        });
      });
      adapter.on('GET', '/api/shop/variants/42/images', (options) =>
          const FakeResponse(<String>[]));
      adapter.on('PUT', '/api/shop/variants/42', (options) {
        saved = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({'productVariantId': 42});
      });
      final repository =
          AdminProductsRepository(apiClient: buildTestApiClient(adapter));

      await tester.pumpWidget(ProviderScope(
        overrides: [
          adminProductsRepositoryProvider.overrideWithValue(repository),
        ],
        child: const MaterialApp(
          home: Scaffold(
            body: AdminVariantFormDialog(
              productId: 9,
              variant: ProductVariant(
                id: 42,
                unit: '8 GB + 256 GB',
                available: true,
                mrp: 35000,
                sellingPrice: 30000,
              ),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Visit to Buy'), findsWidgets);
      await tester.tap(find.widgetWithText(FilledButton, 'Save'));
      await tester.pumpAndSettle();

      expect(saved, isNotNull);
      expect(saved!['commerceMode'], 'VISIT_TO_BUY',
          reason: 'reopening and saving must not apply the create default '
              'ONLINE_PURCHASE to an existing listing');
      expect(saved!['priceMode'], 'STARTING_FROM');
    });

    test('updateVariant goes to the shop route, never /api/product-variants',
        () async {
      final adapter = FakeHttpClientAdapter();
      String? calledPath;
      Map<String, dynamic>? sentBody;

      adapter.on('PUT', '/api/shop/variants/42', (options) {
        calledPath = options.path;
        sentBody = options.data as Map<String, dynamic>;
        return const FakeResponse({'productVariantId': 42});
      });

      final repository =
          AdminProductsRepository(apiClient: buildTestApiClient(adapter));

      await repository.updateVariant(
        variantId: 42,
        label: '8 GB + 128 GB',
        mrp: 35000,
        sellingPrice: 30000,
        costPrice: 29000,
        available: true,
        attributes: const [
          VariantAttribute(name: 'RAM', value: '8 GB'),
          VariantAttribute(name: 'Storage', value: '128 GB'),
        ],
      );

      expect(calledPath, '/api/shop/variants/42',
          reason: 'the platform catalogue route answers 403 to a shopkeeper');
      // THE EXACT NUMBERS FROM THE DEVICE REPORT.
      expect(sentBody!['mrp'], 35000);
      expect(sentBody!['sellingPrice'], 30000);
      expect(sentBody!['costPrice'], 29000);
      expect(sentBody!['attributes'], [
        {'name': 'RAM', 'value': '8 GB'},
        {'name': 'Storage', 'value': '128 GB'},
      ]);
    });

    test('variant photos go to the shop route too', () async {
      final adapter = FakeHttpClientAdapter();
      String? calledPath;
      adapter.on('PUT', '/api/shop/variants/42/images', (options) {
        calledPath = options.path;
        return const FakeResponse(<String>['a.jpg']);
      });

      final repository =
          AdminProductsRepository(apiClient: buildTestApiClient(adapter));
      await repository.setVariantImages(42, const ['a.jpg']);

      expect(calledPath, '/api/shop/variants/42/images',
          reason: 'photo saving failed on a real device for exactly the same '
              'reason the price save did');
    });

    test('a new department is the shop\'s own, not the marketplace taxonomy',
        () async {
      final adapter = FakeHttpClientAdapter();
      String? calledPath;
      adapter.on('POST', '/api/shop/categories', (options) {
        calledPath = options.path;
        return const FakeResponse({'id': 3, 'name': 'charger', 'active': true});
      });

      final repository =
          AdminProductsRepository(apiClient: buildTestApiClient(adapter));
      final created = await repository.createCategory(name: 'charger');

      expect(calledPath, '/api/shop/categories',
          reason: 'posting to /api/categories is what produced "You don\'t '
              'have permission to do that" on a real phone');
      expect(created.name, 'charger');
    });

    test('updateProduct goes to the shop route, never /api/products', () async {
      final adapter = FakeHttpClientAdapter();
      String? calledPath;
      Map<String, dynamic>? sentBody;

      adapter.on('PUT', '/api/shop/products/7', (options) {
        calledPath = options.path;
        sentBody = options.data as Map<String, dynamic>;
        return const FakeResponse({'id': 7, 'name': 'moto edge 50 pro'});
      });

      final repository =
          AdminProductsRepository(apiClient: buildTestApiClient(adapter));
      await repository.updateProduct(
        productId: 7,
        name: 'moto edge 50 pro',
        brand: 'motorola',
        categoryId: 3,
        active: true,
      );

      expect(calledPath, '/api/shop/products/7',
          reason: 'PUT /api/products/{id} is the platform catalogue and answered '
              '403 "You don\'t have permission to do that" on a real phone, on a '
              'product the merchant had just been shown');
      expect(sentBody!['categoryId'], 3);
      expect(sentBody!['active'], true);
    });
  });

  group('a missing route is not a missing row', () {
    DioException notFound(String message) => DioException(
          requestOptions: RequestOptions(path: '/api/shop/variants/42'),
          response: Response(
            requestOptions: RequestOptions(path: '/api/shop/variants/42'),
            statusCode: 404,
            data: {'status': 404, 'message': message},
          ),
          type: DioExceptionType.badResponse,
        );

    test('a routing 404 is reported as a version problem, not a data problem',
        () {
      // THE FALSE ALARM THIS PREVENTS. A merchant admin build newer than the
      // deployed backend called a route the server did not have yet. The
      // server said "No endpoint exists at ..."; the app said "This shop no
      // longer lists that item" while showing that item, its price and
      // "In stock" - and an investigation went looking for corrupt ownership
      // rows that did not exist.
      final error = notFound('No endpoint exists at /api/shop/variants/42');

      expect(meansEndpointMissing(error), isTrue);
      final message = extractErrorMessage(error);
      expect(message, contains('newer than the server'));
      expect(message, isNot(contains('no longer lists')));
      expect(message, contains('Your data is fine'));
    });

    test('an ordinary 404 still means the row is gone', () {
      final error = notFound('This shop does not list that item.');

      expect(meansEndpointMissing(error), isFalse);
      // Not the version-skew sentence: the server judged this request and the
      // row really is gone, so the ordinary "it is not there" wording stands.
      final message = extractErrorMessage(error);
      expect(message, isNot(contains('newer than the server')));
      expect(message, 'That is no longer available.');
    });

    test('a 403 is never mistaken for a missing route', () {
      final error = DioException(
        requestOptions: RequestOptions(path: '/api/products/7'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/products/7'),
          statusCode: 403,
          data: {'status': 403, 'message': 'No endpoint exists at /api/products/7'},
        ),
        type: DioExceptionType.badResponse,
      );
      expect(meansEndpointMissing(error), isFalse,
          reason: 'the status is half the signal - a 403 is a judgement, not a '
              'missing route');
    });
  });

  group('the variant form asks each trade its own question', () {
    test('a phone is described by RAM and storage, not by pack size', () {
      final names = VariantAttributeTemplates.forCategory('Mobile Phones');
      expect(names, contains('RAM'));
      expect(names, contains('Storage'));
      expect(names, isNot(contains('Pack size')),
          reason: 'a phone merchant was being asked to type 8 under "Pack size"');
    });

    test('a kirana still gets pack size and unit', () {
      expect(VariantAttributeTemplates.forCategory('Atta, Rice & Dal'),
          containsAll(<String>['Pack size', 'Unit']));
    });

    test('clothing and footwear are sized, sarees are material', () {
      expect(VariantAttributeTemplates.forCategory('Shoes'),
          containsAll(<String>['Size', 'Colour']));
      expect(VariantAttributeTemplates.forCategory('Silk Sarees'),
          containsAll(<String>['Colour', 'Material']));
    });

    test('a trade nobody anticipated still gets somewhere to type', () {
      // The point of a generic model: an unknown category is not an error and
      // not an empty form.
      expect(VariantAttributeTemplates.forCategory('Fishing Tackle'),
          isNotEmpty);
      expect(VariantAttributeTemplates.forCategory(null), isNotEmpty);
    });

    test('a variant reads as its values joined, in order', () {
      expect(
          VariantAttribute.describe(const [
            VariantAttribute(name: 'RAM', value: '8 GB'),
            VariantAttribute(name: 'Storage', value: '128 GB'),
            VariantAttribute(name: 'Colour', value: 'Black'),
          ]),
          '8 GB · 128 GB · Black');
    });

    test('blank rows are not part of the description', () {
      expect(
          VariantAttribute.describe(const [
            VariantAttribute(name: 'RAM', value: '8 GB'),
            VariantAttribute(name: 'Colour', value: '   '),
          ]),
          '8 GB');
    });
  });
}
