import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/core/marketplace/marketplace_repository.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_feed_models.dart';
import 'package:gpstore/features/marketplace/presentation/marketplace_feed_provider.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  test('page 1, 2 and 3 append exact identities, dedupe and exhaust',
      () async {
    final repository = _PagingMarketplaceRepository({
      0: [for (var i = 0; i < 18; i++) _card(i, shopId: 10 + i)],
      1: [
        _card(0, shopId: 10),
        for (var i = 18; i < 35; i++) _card(i, shopId: 10 + i),
      ],
      2: [_card(35, shopId: 45)],
    });
    final container = ProviderContainer(overrides: [
      deliveryPinProvider.overrideWith((ref) => (lat: 27.16, lng: 83.94)),
      marketplaceRepositoryProvider.overrideWithValue(repository),
    ]);
    addTearDown(container.dispose);

    final subscription = container.listen(
      marketplaceHomeAllFeedProvider,
      (_, __) {},
      fireImmediately: true,
    );
    addTearDown(subscription.close);
    await _until(() =>
        container.read(marketplaceHomeAllFeedProvider).cards.length == 18);
    expect(container.read(marketplaceHomeAllFeedProvider).cards, hasLength(18));

    await container
        .read(marketplaceHomeAllFeedProvider.notifier)
        .loadMore();
    expect(container.read(marketplaceHomeAllFeedProvider).cards, hasLength(35),
        reason: 'the repeated first-page identity must not be appended');

    await container
        .read(marketplaceHomeAllFeedProvider.notifier)
        .loadMore();
    final state = container.read(marketplaceHomeAllFeedProvider);
    expect(state.cards, hasLength(36));
    expect(state.hasNext, isFalse);
    expect(repository.pages, [0, 1, 2]);

    await container
        .read(marketplaceHomeAllFeedProvider.notifier)
        .loadMore();
    expect(repository.pages, [0, 1, 2],
        reason: 'an exhausted feed must not repeat its final request');
  });

  test('a failed next page keeps appended cards and retry resumes that page',
      () async {
    final repository = _PagingMarketplaceRepository({
      0: [for (var i = 0; i < 18; i++) _card(i, shopId: 100 + i)],
      1: [_card(18, shopId: 118)],
    }, failPageOnce: 1);
    final container = ProviderContainer(overrides: [
      deliveryPinProvider.overrideWith((ref) => (lat: 27.16, lng: 83.94)),
      marketplaceRepositoryProvider.overrideWithValue(repository),
    ]);
    addTearDown(container.dispose);

    final subscription = container.listen(
      marketplaceHomeAllFeedProvider,
      (_, __) {},
      fireImmediately: true,
    );
    addTearDown(subscription.close);
    await _until(() =>
        container.read(marketplaceHomeAllFeedProvider).cards.length == 18);
    await container
        .read(marketplaceHomeAllFeedProvider.notifier)
        .loadMore();
    await _until(
        () => container.read(marketplaceHomeAllFeedProvider).error != null);
    var state = container.read(marketplaceHomeAllFeedProvider);
    expect(state.cards, hasLength(18));
    expect(state.error, isNotNull);

    await container.read(marketplaceHomeAllFeedProvider.notifier).retry();
    state = container.read(marketplaceHomeAllFeedProvider);
    expect(state.cards, hasLength(19));
    expect(state.error, isNull);
    expect(state.hasNext, isFalse);
    expect(repository.pages, [0, 1, 1]);
  });
}

Future<void> _until(bool Function() condition) async {
  for (var attempt = 0; attempt < 50 && !condition(); attempt++) {
    await Future<void>.delayed(Duration.zero);
  }
  expect(condition(), isTrue, reason: 'asynchronous provider state did not settle');
}

MarketplaceCard _card(int id, {required int shopId}) => MarketplaceCard(
      productId: id + 1,
      productVariantId: 1000 + id,
      shopId: shopId,
      name: 'Listing $id',
      commerceMode: CommerceMode.buyOnline,
      priceMode: ListingPriceMode.exact,
      addable: true,
    );

class _PagingMarketplaceRepository extends MarketplaceRepository {
  _PagingMarketplaceRepository(
    this.responses, {
    this.failPageOnce,
  }) : super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  final Map<int, List<MarketplaceCard>> responses;
  final int? failPageOnce;
  final List<int> pages = [];
  bool _failed = false;

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
    if (page == failPageOnce && !_failed) {
      _failed = true;
      throw StateError('temporary marketplace failure');
    }
    return responses[page] ?? const [];
  }
}
