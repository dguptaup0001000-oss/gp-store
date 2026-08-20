import 'package:flutter/foundation.dart';

import '../domain/brand_models.dart';
import '../domain/product_models.dart';

/// Fetches one page of one brand. Injected so this controller can be tested
/// without a network, a Dio client, or a running app.
typedef BrandPageFetcher = Future<({List<Product> products, int totalElements, int totalPages})> Function({
  required String brand,
  BrandSortOption? sort,
  bool inStockOnly,
  String? keyword,
  required int page,
});

/// The catalogue's brand order. One small cached request; see
/// ProductRepository.findBrandsWithProductCounts - it is `order by brand asc`
/// and only returns brands that currently have an active product, so it is
/// both deterministic and already filtered.
typedef BrandListFetcher = Future<List<BrandSummary>> Function();

/// One brand's slice of the feed.
@immutable
class BrandFeedSection {
  const BrandFeedSection({
    required this.brand,
    required this.products,
    required this.nextPage,
    required this.hasMorePages,
  });

  final BrandSummary brand;
  final List<Product> products;

  /// The page number to request next. Held rather than derived from
  /// products.length because the length can be smaller than pages x size once
  /// cross-brand duplicates are filtered out, and deriving it would then
  /// re-request a page that was already consumed.
  final int nextPage;
  final bool hasMorePages;

  BrandFeedSection copyWith({
    List<Product>? products,
    int? nextPage,
    bool? hasMorePages,
  }) {
    return BrandFeedSection(
      brand: brand,
      products: products ?? this.products,
      nextPage: nextPage ?? this.nextPage,
      hasMorePages: hasMorePages ?? this.hasMorePages,
    );
  }
}

/// Drives the brand page's one continuous feed: the brand you opened, then
/// the next brand, then the next, until the catalogue runs out.
///
/// WHY A CONTROLLER AND NOT SCREEN STATE. Three of the rules this has to
/// honour - never fire a duplicate request, never append a brand twice,
/// never show the end message early - are only checkable if the state
/// machine can be driven directly. Behind a widget they can only be checked
/// by scrolling a real device and hoping.
///
/// NO NEW BACKEND. The catalogue's brand order comes from the existing
/// /api/products/brands (cached, one row per brand, already excludes brands
/// with no active products) and each brand's products from the existing
/// /api/products/brand/{brand}. "The next brand" is simply the next entry in
/// that ordered list, so nothing here invents data.
class BrandFeedController extends ChangeNotifier {
  BrandFeedController({
    required this.anchorBrand,
    required BrandListFetcher loadBrands,
    required BrandPageFetcher fetchPage,
  })  : _loadBrands = loadBrands,
        _fetchPage = fetchPage;

  /// The brand the customer actually tapped. The feed starts here, and in
  /// search mode it never leaves here.
  final BrandSummary anchorBrand;

  final BrandListFetcher _loadBrands;
  final BrandPageFetcher _fetchPage;

  List<BrandSummary> _order = const [];
  int _nextBrandIndex = 0;

  final List<BrandFeedSection> _sections = [];

  /// Brands already in the feed. Guards the "same brand appended twice" rule
  /// independently of the index, so even a mis-stepped index cannot duplicate
  /// a section.
  final Set<String> _loadedBrands = {};

  /// Product ids anywhere in the feed. A product carries exactly one brand,
  /// so this should never fire across sections - it exists for the case that
  /// does happen: the same page arriving twice because a request was retried
  /// or a scroll event slipped through.
  final Set<int> _seenProductIds = {};

  bool _isInitialLoading = true;
  bool _isLoadingMore = false;
  bool _reachedEnd = false;
  String? _errorMessage;

  /// The single lock. Every load path goes through it, so however many scroll
  /// events fire during a fast flick, only one request is ever in flight.
  bool _busy = false;

  /// Bumped whenever the filters change. A response that comes back carrying
  /// a stale generation is discarded rather than appended - otherwise a slow
  /// request from the old sort lands on top of the new results.
  int _generation = 0;

  BrandSortOption? _sort;
  bool _inStockOnly = false;
  String _keyword = '';

  List<BrandFeedSection> get sections => List.unmodifiable(_sections);
  bool get isInitialLoading => _isInitialLoading;
  bool get isLoadingMore => _isLoadingMore;
  bool get reachedEnd => _reachedEnd;
  String? get errorMessage => _errorMessage;
  BrandSortOption? get sort => _sort;
  bool get inStockOnly => _inStockOnly;
  String get keyword => _keyword;

  /// True while a keyword is active. Search stays inside the brand the page
  /// was opened for - rolling a search into other brands would answer a
  /// question the customer did not ask.
  bool get isSearching => _keyword.isNotEmpty;

  bool get isEmpty => _sections.every((s) => s.products.isEmpty);

  /// Loads the brand order and the anchor brand's first page.
  Future<void> start() async {
    _isInitialLoading = true;
    _errorMessage = null;
    notifyListeners();
    await _resetAndLoad();
  }

  /// Re-runs the current query from scratch. Used by retry and by every
  /// filter change.
  Future<void> _resetAndLoad() async {
    final generation = ++_generation;

    _sections.clear();
    _loadedBrands.clear();
    _seenProductIds.clear();
    _nextBrandIndex = 0;
    _reachedEnd = false;
    _errorMessage = null;
    // Cleared so a reset that lands while a previous load is in flight is not
    // blocked by that load's lock; the generation check discards its result.
    _busy = false;

    try {
      // In search mode the brand order is irrelevant - only the anchor is
      // ever shown - so the request is skipped entirely rather than made and
      // thrown away.
      _order = isSearching ? [anchorBrand] : _buildOrder(await _loadBrands());
    } catch (e) {
      if (generation != _generation) return;
      // A failed brand list is not a failed page: the anchor brand is still
      // browsable on its own, which is exactly what this screen did before
      // the feed existed. Degrade to that rather than showing an error.
      debugPrint('Could not load the brand order, staying within ${anchorBrand.brand}: $e');
      _order = [anchorBrand];
    }

    if (generation != _generation) return;

    await _loadNextChunk(generation);

    if (generation != _generation) return;
    _isInitialLoading = false;
    notifyListeners();
  }

  /// The anchor first, then every other brand in catalogue order, wrapping
  /// past the end.
  ///
  /// Wrapping matters: opening the alphabetically last brand would otherwise
  /// end the feed immediately, and "continue until all available brands are
  /// finished" means all of them, not the ones that happen to sort after
  /// wherever the customer started.
  List<BrandSummary> _buildOrder(List<BrandSummary> all) {
    final index = all.indexWhere((b) => b.brand == anchorBrand.brand);
    if (index < 0) {
      // The anchor is not in the list (its last product just went inactive,
      // or the cached list is a moment stale). Still show it first - the
      // customer asked for it - then everything else.
      return [anchorBrand, ...all];
    }
    return [...all.sublist(index), ...all.sublist(0, index)];
  }

  /// Loads whatever comes next: another page of the current brand, or the
  /// first page of the next brand.
  ///
  /// Safe to call on every scroll frame. It no-ops while a request is in
  /// flight and once the catalogue is exhausted, which is what makes fast
  /// scrolling harmless.
  Future<void> advance() async {
    if (_busy || _reachedEnd || _isInitialLoading) return;
    await _loadNextChunk(_generation);
    notifyListeners();
  }

  Future<void> _loadNextChunk(int generation) async {
    if (_busy) return;
    _busy = true;
    _isLoadingMore = _sections.isNotEmpty;
    if (_isLoadingMore) notifyListeners();

    final sectionsBefore = _sections.length;
    final productsBefore = _loadedProductCount;
    var failed = false;

    try {
      final current = _sections.isEmpty ? null : _sections.last;

      if (current != null && current.hasMorePages) {
        await _loadMorePagesOf(_sections.length - 1, generation);
      } else {
        await _appendNextBrand(generation);
      }
    } catch (e) {
      if (generation != _generation) return;
      failed = true;
      // An error part-way through must not wipe what is already on screen -
      // the customer keeps everything they have scrolled past and gets a
      // retry for the part that failed.
      _errorMessage = _describe(e);
    } finally {
      if (generation == _generation) {
        _busy = false;
        _isLoadingMore = false;
      }
    }

    if (generation != _generation) return;

    // Nothing was appended, nothing failed, and the catalogue is not
    // finished: _appendNextBrand hit its per-batch cap on empty brands.
    //
    // This has to keep itself going. The screen only calls advance() when a
    // scroll event fires, and a batch that appended nothing grew the page by
    // nothing - so there is no scroll to come, and the feed would stall on a
    // blank footer with neither products nor an end message. Continuing on a
    // fresh async turn rather than looping inline keeps each batch bounded
    // and lets the frame render in between.
    // Progress means products OR a section - not sections alone. Loading
    // another page into the brand already on screen leaves the section count
    // unchanged, and treating that as "no progress" made this continue
    // immediately, pulling an entire brand in one scroll instead of a page.
    final madeProgress = _sections.length != sectionsBefore || _loadedProductCount != productsBefore;

    if (!failed && !_reachedEnd && !madeProgress && _sections.isNotEmpty) {
      await Future<void>.delayed(Duration.zero);
      if (generation != _generation) return;
      await _loadNextChunk(generation);
    }
  }

  Future<void> _loadMorePagesOf(int sectionIndex, int generation) async {
    final section = _sections[sectionIndex];
    final result = await _fetchPage(
      brand: section.brand.brand,
      sort: _sort,
      inStockOnly: _inStockOnly,
      keyword: isSearching ? _keyword : null,
      page: section.nextPage,
    );

    if (generation != _generation) return;

    final fresh = _dedupe(result.products);
    _sections[sectionIndex] = section.copyWith(
      products: [...section.products, ...fresh],
      nextPage: section.nextPage + 1,
      hasMorePages: section.nextPage + 1 < result.totalPages,
    );
  }

  Future<void> _appendNextBrand(int generation) async {
    // Skip brands already in the feed rather than trusting the index alone.
    while (_nextBrandIndex < _order.length && _loadedBrands.contains(_order[_nextBrandIndex].brand)) {
      _nextBrandIndex++;
    }

    if (_nextBrandIndex >= _order.length) {
      // THE ONLY PLACE THE FEED IS ALLOWED TO END. Reached when the brand
      // list is exhausted - never because one brand ran out of pages.
      _reachedEnd = true;
      return;
    }

    // A brand can legitimately come back empty - "In stock only" with nothing
    // in stock, or a search matching nothing in it. Appending an empty
    // section would print a header over dead space, so empty brands are
    // skipped. Skipped after asking rather than filtered up front, because
    // only the server knows what the current filters leave behind.
    //
    // Bounded rather than recursive: with a strict filter, dozens of brands
    // in a row can come back empty, and one scroll event must not turn into
    // dozens of chained requests. Whatever is left is picked up by the next
    // advance() a moment later.
    const maxEmptyBrandsPerAdvance = 5;

    for (var attempt = 0; attempt < maxEmptyBrandsPerAdvance; attempt++) {
      if (_nextBrandIndex >= _order.length) {
        _reachedEnd = true;
        return;
      }

      final brand = _order[_nextBrandIndex];
      final result = await _fetchPage(
        brand: brand.brand,
        sort: _sort,
        inStockOnly: _inStockOnly,
        keyword: isSearching ? _keyword : null,
        page: 0,
      );

      if (generation != _generation) return;

      _nextBrandIndex++;
      _loadedBrands.add(brand.brand);

      final fresh = _dedupe(result.products);

      if (fresh.isEmpty && result.totalElements == 0) {
        // Skip past brands already in the feed before the next attempt.
        while (_nextBrandIndex < _order.length && _loadedBrands.contains(_order[_nextBrandIndex].brand)) {
          _nextBrandIndex++;
        }
        continue;
      }

      _sections.add(BrandFeedSection(
        brand: brand,
        products: fresh,
        nextPage: 1,
        hasMorePages: result.totalPages > 1,
      ));
      return;
    }
  }

  int get _loadedProductCount =>
      _sections.fold<int>(0, (total, section) => total + section.products.length);

  List<Product> _dedupe(List<Product> incoming) {
    final fresh = <Product>[];
    for (final product in incoming) {
      if (_seenProductIds.add(product.id)) fresh.add(product);
    }
    return fresh;
  }

  Future<void> setSort(BrandSortOption? value) async {
    if (value == _sort) return; // no refetch for a no-op choice
    _sort = value;
    await _restart();
  }

  Future<void> setInStockOnly(bool value) async {
    if (value == _inStockOnly) return;
    _inStockOnly = value;
    await _restart();
  }

  Future<void> setKeyword(String value) async {
    final trimmed = value.trim();
    if (trimmed == _keyword) return;
    _keyword = trimmed;
    await _restart();
  }

  Future<void> retry() => _restart();

  /// A filter change rebuilds the feed rather than patching it.
  ///
  /// Sorting is a property of a result set, so keeping already-loaded
  /// products and appending newly-sorted ones below would produce a list that
  /// is sorted in stripes - and re-sorting locally would be a lie, since the
  /// server has pages this client has never seen. The infinite-brand
  /// mechanism is untouched by this; only the contents are rebuilt.
  Future<void> _restart() async {
    _isInitialLoading = true;
    notifyListeners();
    await _resetAndLoad();
  }

  String _describe(Object error) {
    final text = error.toString();
    return text.isEmpty ? 'Something went wrong' : text;
  }
}
