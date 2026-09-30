import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/core/marketplace/marketplace_repository.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_feed_models.dart';
import 'package:gpstore/features/marketplace/presentation/marketplace_feed_provider.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  test('Soap feed is server-filtered, three-up paged, deduped and infinite',
      () async {
    final repository = _CategoryRepository({
      0: [for (var i = 0; i < 18; i++) _card(i)],
      1: [_card(0), _card(18)],
    });
    final container = ProviderContainer(overrides: [
      deliveryPinProvider.overrideWith((ref) => (lat: 27.16, lng: 83.94)),
      marketplaceShopFilterProvider.overrideWith((ref) => 44),
      marketplaceRepositoryProvider.overrideWithValue(repository),
    ]);
    addTearDown(container.dispose);

    final provider = marketplaceCategoryFeedProvider(7);
    final subscription = container.listen(provider, (_, __) {}, fireImmediately: true);
    addTearDown(subscription.close);
    await _until(() => container.read(provider).cards.length == 18);

    await container.read(provider.notifier).loadMore();

    final state = container.read(provider);
    expect(state.cards, hasLength(19),
        reason: 'the repeated listing identity must not be appended');
    expect(state.hasNext, isFalse);
    expect(repository.pages, [0, 1]);
    expect(repository.categoryIds, everyElement(7));
    expect(repository.shopIds, everyElement(44));
    expect(repository.sizes, everyElement(18));
  });
}

Future<void> _until(bool Function() condition) async {
  for (var attempt = 0; attempt < 50 && !condition(); attempt++) {
    await Future<void>.delayed(Duration.zero);
  }
  expect(condition(), isTrue);
}

MarketplaceCard _card(int id) => MarketplaceCard(
      productId: id + 1,
      productVariantId: 1000 + id,
      shopId: 44,
      name: 'Soap ${id + 1}',
      commerceMode: CommerceMode.buyOnline,
      priceMode: ListingPriceMode.exact,
      addable: true,
      sellingPrice: 20,
    );

class _CategoryRepository extends MarketplaceRepository {
  _CategoryRepository(this.responses)
      : super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  final Map<int, List<MarketplaceCard>> responses;
  final List<int> pages = [];
  final List<int?> categoryIds = [];
  final List<int?> shopIds = [];
  final List<int> sizes = [];

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
    shopIds.add(shopId);
    sizes.add(size);
    return responses[page] ?? const [];
  }
}
