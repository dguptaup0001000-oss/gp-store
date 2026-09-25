import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../domain/marketplace_feed_models.dart';

/// State of the marketplace feed: what is for sale near this customer.
class MarketplaceFeedState {
  const MarketplaceFeedState({
    this.cards = const [],
    this.nextPage = 0,
    this.hasNext = true,
    this.isLoadingMore = false,
    this.needsAddress = false,
    this.error,
  });

  final List<MarketplaceCard> cards;
  final int nextPage;
  final bool hasNext;
  final bool isLoadingMore;

  /// The customer has no placeable address, so nobody can be told which shops
  /// deliver to them. A real state on a fresh install, and a different screen
  /// from "nothing is nearby" - one asks for an address, the other does not.
  final bool needsAddress;

  final Object? error;

  bool get isEmpty => cards.isEmpty;

  MarketplaceFeedState copyWith({
    List<MarketplaceCard>? cards,
    int? nextPage,
    bool? hasNext,
    bool? isLoadingMore,
    bool? needsAddress,
    Object? error,
    bool clearError = false,
  }) {
    return MarketplaceFeedState(
      cards: cards ?? this.cards,
      nextPage: nextPage ?? this.nextPage,
      hasNext: hasNext ?? this.hasNext,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      needsAddress: needsAddress ?? this.needsAddress,
      error: clearError ? null : (error ?? this.error),
    );
  }
}

/// Which mode the marketplace screens are currently showing.
///
/// ONE PROVIDER RATHER THAN THREE SCREENS. Buy Online, Visit to Buy and
/// Service at Shop are the same marketplace asked a different question, so
/// switching between them changes a value here rather than pushing a
/// different app.
final marketplaceModeFilterProvider =
    StateProvider<CommerceMode?>((ref) => null);

/// Null means ALL shops near the selected customer address.
final marketplaceShopFilterProvider = StateProvider<int?>((ref) => null);

/// Drives the marketplace feed, one server page at a time.
///
/// Keeps the three guards the shop feed already learned it needed: an
/// in-flight flag so a scroll flick does not issue a dozen identical
/// requests, a seen-id set because Flutter throws on duplicate keys, and the
/// server's own "is there more" rather than inferring it from a short page.
class MarketplaceFeedController
    extends AutoDisposeAsyncNotifier<MarketplaceFeedState> {
  static const _pageSize = 20;

  final Set<String> _seenIds = <String>{};

  @override
  Future<MarketplaceFeedState> build() async {
    _seenIds.clear();
    final pin = ref.watch(deliveryPinProvider);
    final mode = ref.watch(marketplaceModeFilterProvider);
    final shopId = ref.watch(marketplaceShopFilterProvider);

    if (pin == null) {
      return const MarketplaceFeedState(
          hasNext: false, needsAddress: true);
    }

    final cards = await ref.read(marketplaceRepositoryProvider).feed(
          latitude: pin.lat,
          longitude: pin.lng,
          mode: mode,
          shopId: shopId,
          page: 0,
          size: _pageSize,
        );
    return MarketplaceFeedState(
      cards: _dedupe(cards),
      nextPage: 1,
      // The endpoint returns a bare list, so a short page is the end of it.
      hasNext: cards.length >= _pageSize,
    );
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.isLoadingMore || !current.hasNext) return;

    final pin = ref.read(deliveryPinProvider);
    if (pin == null) return;

    state = AsyncData(current.copyWith(isLoadingMore: true, clearError: true));
    try {
      final cards = await ref.read(marketplaceRepositoryProvider).feed(
            latitude: pin.lat,
            longitude: pin.lng,
            mode: ref.read(marketplaceModeFilterProvider),
            shopId: ref.read(marketplaceShopFilterProvider),
            page: current.nextPage,
            size: _pageSize,
          );
      state = AsyncData(current.copyWith(
        // Append, never replace: replacing resets the customer's scroll
        // position to the top on every page.
        cards: [...current.cards, ..._dedupe(cards)],
        nextPage: current.nextPage + 1,
        hasNext: cards.length >= _pageSize,
        isLoadingMore: false,
        clearError: true,
      ));
    } catch (e) {
      // What is already on screen stays on screen; a failed page 7 must not
      // blank out pages 1-6 the customer is reading.
      state = AsyncData(current.copyWith(isLoadingMore: false, error: e));
    }
  }

  Future<void> retryLoadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.isLoadingMore) return;
    state = AsyncData(current.copyWith(clearError: true));
    await loadMore();
  }

  List<MarketplaceCard> _dedupe(List<MarketplaceCard> incoming) {
    final out = <MarketplaceCard>[];
    for (final card in incoming) {
      if (_seenIds.add(card.feedKey)) {
        out.add(card);
      }
    }
    return out;
  }
}

final marketplaceFeedProvider = AutoDisposeAsyncNotifierProvider<
    MarketplaceFeedController, MarketplaceFeedState>(
  MarketplaceFeedController.new,
);
