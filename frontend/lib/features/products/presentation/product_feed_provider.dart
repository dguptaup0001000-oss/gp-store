import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/products_repository.dart';
import '../domain/product_models.dart';
import 'products_providers.dart';

/// State of the endless home feed.
///
/// Deliberately one immutable object rather than several separate providers:
/// "which products do we have", "is another page loading", and "is there
/// more" have to change together. Split across providers they can be read in
/// inconsistent combinations mid-update, which is how a list ends up showing
/// a spinner after it has already reached the end.
class ProductFeedState {
  const ProductFeedState({
    this.products = const [],
    this.nextPage = 0,
    this.hasNext = true,
    this.isLoadingMore = false,
    this.error,
  });

  final List<Product> products;
  final int nextPage;
  final bool hasNext;
  final bool isLoadingMore;

  /// Error from loading a FURTHER page, while products are already on
  /// screen. A first-page failure surfaces through the AsyncValue instead,
  /// because that case has nothing to show yet.
  final Object? error;

  bool get isEmpty => products.isEmpty;

  ProductFeedState copyWith({
    List<Product>? products,
    int? nextPage,
    bool? hasNext,
    bool? isLoadingMore,
    Object? error,
    bool clearError = false,
  }) {
    return ProductFeedState(
      products: products ?? this.products,
      nextPage: nextPage ?? this.nextPage,
      hasNext: hasNext ?? this.hasNext,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      error: clearError ? null : (error ?? this.error),
    );
  }
}

/// Drives the home screen's endless product feed.
///
/// The home screen used to end after New Arrivals - three carousels and
/// nothing more, no matter how large the catalogue. This is what lets it
/// keep going, one server-side page at a time, so a catalogue of thousands
/// is never held in memory or requested at once.
///
/// THREE GUARDS, each for a failure this design invites:
///
/// 1. An in-flight flag. Scroll listeners fire many times per second near
///    the bottom, so without it one flick issues a dozen identical requests
///    for the same page and appends the same products repeatedly.
///
/// 2. A seen-id set. Even with the in-flight guard, a product can arrive
///    twice - a retry after an error, or a catalogue edit between pages.
///    Flutter also throws outright on duplicate keys in a list, so this is
///    a crash guard as much as a correctness one.
///
/// 3. hasNext from the server. When it says the last page has been served,
///    loadMore becomes a no-op, so reaching the bottom stops asking instead
///    of requesting empty pages forever.
class ProductFeedController extends AutoDisposeAsyncNotifier<ProductFeedState> {
  static const _pageSize = 20;

  /// Ids already appended. Kept beside the list rather than derived from it
  /// on every call, because that check runs per product per page and the
  /// list grows without bound as the customer scrolls.
  final Set<int> _seenIds = <int>{};

  ProductsRepository get _repository => ref.read(productsRepositoryProvider);

  @override
  Future<ProductFeedState> build() async {
    _seenIds.clear();
    final page = await _repository.fetchFeed(page: 0, size: _pageSize);
    final unique = _dedupe(page.products);
    return ProductFeedState(
      products: unique,
      nextPage: 1,
      hasNext: page.hasNext,
    );
  }

  /// Loads the next page and APPENDS it. Safe to call on every scroll event.
  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null) return;

    // Guards 1 and 3: already fetching, or the server said there is nothing
    // left. Both make this a silent no-op rather than a wasted request.
    if (current.isLoadingMore || !current.hasNext) return;

    state = AsyncData(current.copyWith(isLoadingMore: true, clearError: true));

    try {
      final page = await _repository.fetchFeed(page: current.nextPage, size: _pageSize);
      final fresh = _dedupe(page.products);

      state = AsyncData(current.copyWith(
        // Append, never replace - replacing would reset the customer's
        // scroll position to the top on every page load.
        products: [...current.products, ...fresh],
        nextPage: current.nextPage + 1,
        hasNext: page.hasNext,
        isLoadingMore: false,
        clearError: true,
      ));
    } catch (e) {
      // The products already on screen stay on screen. A failed page 7 must
      // not blank out pages 1-6 the customer is looking at; the footer shows
      // a retry instead.
      state = AsyncData(current.copyWith(isLoadingMore: false, error: e));
    }
  }

  /// Retries the page that just failed, without disturbing what is loaded.
  Future<void> retryLoadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.isLoadingMore) return;
    state = AsyncData(current.copyWith(clearError: true));
    await loadMore();
  }

  /// Guard 2 - drops anything already appended.
  List<Product> _dedupe(List<Product> incoming) {
    final out = <Product>[];
    for (final product in incoming) {
      if (_seenIds.add(product.id)) {
        out.add(product);
      }
    }
    return out;
  }
}

final productFeedProvider =
    AutoDisposeAsyncNotifierProvider<ProductFeedController, ProductFeedState>(
  ProductFeedController.new,
);
