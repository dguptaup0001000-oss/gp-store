import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/core/marketplace/marketplace_repository.dart';
import 'package:gpstore/core/util/app_haptics.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_feed_models.dart';
import 'package:gpstore/features/marketplace/presentation/marketplace_category_products_screen.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/wishlist/domain/wishlist_models.dart';
import 'package:gpstore/features/wishlist/presentation/wishlist_providers.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);
  setUp(() {
    AppHaptics.resetForTest();
    AppHaptics.enabled = false;
  });
  tearDown(() => AppHaptics.enabled = true);

  testWidgets('category uses three columns and loads the next page on scroll',
      (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final repository = _ScreenRepository();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        deliveryPinProvider.overrideWith((ref) => (lat: 27.16, lng: 83.94)),
        marketplaceRepositoryProvider.overrideWithValue(repository),
        wishlistControllerProvider.overrideWith(_EmptyWishlist.new),
      ],
      child: const MaterialApp(
        home: MarketplaceCategoryProductsScreen(
          category: Category(id: 7, name: 'Soap'),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    final grid = tester.widget<SliverGrid>(find.byType(SliverGrid).first);
    final delegate =
        grid.gridDelegate as SliverGridDelegateWithFixedCrossAxisCount;
    expect(delegate.crossAxisCount, 3);
    expect(find.text('Soap 1'), findsOneWidget);

    final scrollable = find.byWidgetPredicate(
      (widget) =>
          widget is Scrollable && widget.axisDirection == AxisDirection.down,
    );
    await tester.scrollUntilVisible(
      find.text('Soap 19'),
      300,
      scrollable: scrollable.first,
    );
    await tester.pumpAndSettle();

    expect(find.text('Soap 19'), findsOneWidget);
    expect(repository.pages, containsAllInOrder([0, 1]));
    expect(repository.categoryIds, everyElement(7));
    expect(tester.takeException(), isNull);
  });
}

class _EmptyWishlist extends WishlistController {
  @override
  Future<List<WishlistItem>> build() async => const [];
}

class _ScreenRepository extends MarketplaceRepository {
  _ScreenRepository()
      : super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  final List<int> pages = [];
  final List<int?> categoryIds = [];

  @override
  Future<List<MarketplaceCard>> feed({
    required double? latitude,
    required double? longitude,
    CommerceMode? mode,
    int? categoryId,
    int? shopId,
    int page = 0,
    int size = 20,
  }) async {
    pages.add(page);
    categoryIds.add(categoryId);
    if (page == 0) {
      return [for (var i = 0; i < 18; i++) _card(i + 1)];
    }
    if (page == 1) return [_card(19)];
    return const [];
  }
}

MarketplaceCard _card(int id) => MarketplaceCard(
      productId: id,
      productVariantId: 1000 + id,
      shopId: 5,
      shopName: 'Nearby Test Shop',
      name: 'Soap $id',
      commerceMode: CommerceMode.buyOnline,
      priceMode: ListingPriceMode.exact,
      addable: true,
      sellingPrice: 40,
    );
