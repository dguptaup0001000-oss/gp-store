import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../domain/marketplace_feed_models.dart';

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

/// A bounded first page for one commerce-mode rail on Customer Home.
///
/// Home must not infer that a mode has no nearby listings just because the
/// first page of the combined feed happened to contain only another mode.
/// Each of the three rails asks the server for its own mode (and the current
/// shop filter), so one rail can be empty without suppressing its siblings.
/// Twelve cards per rail keeps startup bounded; each rail fetches later pages
/// as the customer scrolls horizontally.
class MarketplaceHomeModeFeedState {
  const MarketplaceHomeModeFeedState({
    this.cards = const [],
    this.nextPage = 0,
    this.hasNext = true,
    this.isLoading = false,
    this.isLoadingMore = false,
    this.needsAddress = false,
    this.error,
  });

  final List<MarketplaceCard> cards;
  final int nextPage;
  final bool hasNext;
  final bool isLoading;
  final bool isLoadingMore;
  final bool needsAddress;
  final Object? error;
}

class MarketplaceHomeModeFeedController
    extends StateNotifier<MarketplaceHomeModeFeedState> {
  MarketplaceHomeModeFeedController({
    required Ref ref,
    required CommerceMode mode,
    required ({double lat, double lng})? pin,
    required int? shopId,
  })  : _ref = ref,
        _mode = mode,
        _pin = pin,
        _shopId = shopId,
        super(MarketplaceHomeModeFeedState(
            isLoading: pin != null, needsAddress: pin == null)) {
    if (pin != null) unawaited(_loadInitial());
  }

  static const _pageSize = 12;
  final Ref _ref;
  final CommerceMode _mode;
  final ({double lat, double lng})? _pin;
  final int? _shopId;
  final Set<String> _seen = <String>{};
  bool _disposed = false;

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  Future<List<MarketplaceCard>> _fetch(int page) {
    final pin = _pin;
    if (pin == null) return Future.value(const []);
    return _ref.read(marketplaceRepositoryProvider).feed(
          latitude: pin.lat,
          longitude: pin.lng,
          mode: _mode,
          shopId: _shopId,
          page: page,
          size: _pageSize,
        );
  }

  Future<void> _loadInitial() async {
    try {
      final cards = await _fetch(0);
      if (_disposed) return;
      final unique = _dedupe(cards);
      state = MarketplaceHomeModeFeedState(
        cards: unique,
        nextPage: 1,
        hasNext: cards.length == _pageSize,
        isLoading: false,
      );
    } catch (error) {
      if (_disposed) return;
      state = MarketplaceHomeModeFeedState(isLoading: false, error: error);
    }
  }

  Future<void> loadMore() async {
    final current = state;
    if (current.isLoading || current.isLoadingMore || !current.hasNext) return;
    state = MarketplaceHomeModeFeedState(
      cards: current.cards,
      nextPage: current.nextPage,
      hasNext: current.hasNext,
      isLoadingMore: true,
      needsAddress: current.needsAddress,
    );
    try {
      final cards = await _fetch(current.nextPage);
      if (_disposed) return;
      state = MarketplaceHomeModeFeedState(
        cards: [...current.cards, ..._dedupe(cards)],
        nextPage: current.nextPage + 1,
        hasNext: cards.length >= _pageSize,
      );
    } catch (error) {
      if (_disposed) return;
      state = MarketplaceHomeModeFeedState(
        cards: current.cards,
        nextPage: current.nextPage,
        hasNext: current.hasNext,
        error: error,
      );
    }
  }

  Future<void> retry() async {
    if (state.cards.isEmpty) {
      state = const MarketplaceHomeModeFeedState(isLoading: true);
      await _loadInitial();
    } else {
      await loadMore();
    }
  }

  List<MarketplaceCard> _dedupe(List<MarketplaceCard> incoming) {
    final unique = <MarketplaceCard>[];
    for (final card in incoming) {
      if (_seen.add(card.feedKey)) unique.add(card);
    }
    return unique;
  }
}

final marketplaceHomeModeFeedProvider = StateNotifierProvider.autoDispose
    .family<MarketplaceHomeModeFeedController, MarketplaceHomeModeFeedState,
        CommerceMode>((ref, mode) {
  final pin = ref.watch(deliveryPinProvider);
  final shopId = ref.watch(marketplaceShopFilterProvider);
  return MarketplaceHomeModeFeedController(
    ref: ref,
    mode: mode,
    pin: pin,
    shopId: shopId,
  );
});

/// The pageable mixed-mode tail of Marketplace Home. The three compact
/// carousels answer "what is nearby in each mode?"; this feed lets the
/// customer keep browsing the entire eligible marketplace without fetching
/// thousands of records at startup.
class MarketplaceHomeAllFeedState {
  const MarketplaceHomeAllFeedState({
    this.cards = const [],
    this.nextPage = 0,
    this.hasNext = true,
    this.isLoading = false,
    this.isLoadingMore = false,
    this.error,
  });

  final List<MarketplaceCard> cards;
  final int nextPage;
  final bool hasNext;
  final bool isLoading;
  final bool isLoadingMore;
  final Object? error;
}

class MarketplaceHomeAllFeedController
    extends StateNotifier<MarketplaceHomeAllFeedState> {
  MarketplaceHomeAllFeedController({
    required Ref ref,
    required ({double lat, double lng})? pin,
    required int? shopId,
  })  : _ref = ref,
        _pin = pin,
        _shopId = shopId,
        super(MarketplaceHomeAllFeedState(isLoading: pin != null)) {
    if (pin != null) unawaited(_load(0, replace: true));
  }

  static const pageSize = 24;
  final Ref _ref;
  final ({double lat, double lng})? _pin;
  final int? _shopId;
  final Set<String> _seen = {};
  bool _disposed = false;

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  Future<void> _load(int page, {required bool replace}) async {
    final before = state;
    if (replace) {
      _seen.clear();
      state = const MarketplaceHomeAllFeedState(isLoading: true);
    } else {
      if (before.isLoading || before.isLoadingMore || !before.hasNext) return;
      state = MarketplaceHomeAllFeedState(
        cards: before.cards,
        nextPage: before.nextPage,
        hasNext: before.hasNext,
        isLoadingMore: true,
      );
    }

    try {
      final pin = _pin;
      final cards = pin == null
          ? const <MarketplaceCard>[]
          : await _ref.read(marketplaceRepositoryProvider).feed(
                latitude: pin.lat,
                longitude: pin.lng,
                shopId: _shopId,
                page: page,
                size: pageSize,
              );
      if (_disposed) return;
      final unique = <MarketplaceCard>[];
      for (final card in cards) {
        if (_seen.add(card.feedKey)) unique.add(card);
      }
      final merged = replace ? unique : [...before.cards, ...unique];
      state = MarketplaceHomeAllFeedState(
        cards: merged,
        nextPage: page + 1,
        hasNext: cards.length == pageSize,
      );
    } catch (error) {
      if (_disposed) return;
      state = MarketplaceHomeAllFeedState(
        cards: replace ? const [] : before.cards,
        nextPage: page,
        hasNext: replace ? true : before.hasNext,
        error: error,
      );
    }
  }

  Future<void> loadMore() => _load(state.nextPage, replace: false);

  Future<void> retry() => _load(state.cards.isEmpty ? 0 : state.nextPage,
      replace: state.cards.isEmpty);
}

final marketplaceHomeAllFeedProvider = StateNotifierProvider.autoDispose<
    MarketplaceHomeAllFeedController, MarketplaceHomeAllFeedState>((ref) {
  final pin = ref.watch(deliveryPinProvider);
  final shopId = ref.watch(marketplaceShopFilterProvider);
  return MarketplaceHomeAllFeedController(ref: ref, pin: pin, shopId: shopId);
});
