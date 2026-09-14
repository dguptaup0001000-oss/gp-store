import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/shop_context.dart';
import 'package:gpstore/features/marketplace/presentation/shop_shelf_preview.dart';
import 'package:gpstore/features/products/data/products_repository.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';

/// Looking at another shop's shelf without moving into it.
///
/// THE TWO CLAIMS THAT MATTER. Reading a shop's shelf must name that shop on
/// the request rather than switching the app to it - switching throws away the
/// categories, the feed and the basket pricing of the shop the customer is
/// actually in, and doing that to somebody who only wanted to look is the app
/// making their decision. And nothing on this grid may add to a basket: the
/// basket belongs to the shop the app is acting for, so an ADD here would put
/// one shop's item into another shop's basket at another shop's price.
class _RecordingRepository implements ProductsRepository {
  final List<int?> shopIdsAskedFor = [];

  @override
  Future<ProductPage> fetchFeed({int page = 0, int size = 20, int? shopId}) async {
    shopIdsAskedFor.add(shopId);
    return const ProductPage(
      products: [
        Product(
          id: 1,
          name: 'Aashirvaad Atta',
          variants: [
            ProductVariant(
                id: 11, quantity: 5, unit: 'kg', available: true, sellingPrice: 245),
          ],
        ),
      ],
      page: 0,
      hasNext: false,
      totalElements: 1,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName} is not part of this test');
}

void main() {
  late _RecordingRepository repository;
  late ProviderContainer container;

  Widget host() {
    repository = _RecordingRepository();
    return UncontrolledProviderScope(
      container: container = ProviderContainer(overrides: [
        productsRepositoryProvider.overrideWithValue(repository),
      ]),
      child: const MaterialApp(
        home: Scaffold(
          body: ShopShelfPreview(shopId: 6, shopName: 'Sharma Medical'),
        ),
      ),
    );
  }

  testWidgets('it asks that shop for its shelf, by id', (tester) async {
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    expect(repository.shopIdsAskedFor, [6],
        reason: 'the shelf must name the shop on the request; reading it any '
            'other way means switching the whole app to look at one shop');
  });

  testWidgets('looking does not switch the app to that shop', (tester) async {
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    // THE PROPERTY WORTH BREAKING A BUILD OVER. A customer who opened a
    // competitor's page to compare must come back to their own shop with
    // their basket priced as they left it.
    expect(container.read(shopContextProvider), isNull);
  });

  testWidgets('the shelf shows what is on it, at that shop\'s price',
      (tester) async {
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    expect(find.text('Aashirvaad Atta'), findsOneWidget);
    expect(find.text('₹245'), findsOneWidget);
  });

  testWidgets('there is nothing here that adds to a basket', (tester) async {
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    // Not a styling preference. The basket is scoped to the shop the app is
    // acting for, and this grid is deliberately NOT that shop - so an ADD
    // here has no correct destination.
    expect(find.text('ADD'), findsNothing);
    expect(find.text('Add'), findsNothing);
    expect(find.byIcon(Icons.add), findsNothing);
  });
}
