// hide Category: foundation exports an annotation class of the same name,
// which would make every Category here ambiguous with the domain model.
import 'package:flutter/foundation.dart' hide Category;

import '../domain/product_models.dart';
import '../../../core/logging/app_log.dart';

/// Fetches one page of one category. Injected so the controller can be tested
/// without a network, mirroring BrandFeedController.
typedef CategoryPageFetcher = Future<List<Product>> Function({
  required int categoryId,
  required int page,
  required int size,
});

/// One category's products inside the continuous feed.
class CategoryFeedSection {
  CategoryFeedSection({
    required this.category,
    required this.products,
    required this.nextPage,
    required this.hasMorePages,
  });

  final Category category;
  final List<Product> products;
  final int nextPage;
  final bool hasMorePages;

  CategoryFeedSection copyWith({
    List<Product>? products,
    int? nextPage,
    bool? hasMorePages,
  }) {
    return CategoryFeedSection(
      category: category,
      products: products ?? this.products,
      nextPage: nextPage ?? this.nextPage,
      hasMorePages: hasMorePages ?? this.hasMorePages,
    );
  }
}

/// Scrolling past the end of one category into the next, without stopping.
///
/// DELIBERATELY THE SAME SHAPE AS BrandFeedController, which already solves
/// this problem for brands and has the tests to prove it: roll into the next
/// group rather than ending, skip groups that come back empty, wrap past the
/// end so every group is reachable, page within a group before moving on, and
/// discard responses from a superseded query. Writing a second, differently
/// shaped solution for categories would mean two places to fix the next bug
/// found in either.
///
/// WHAT IT REPLACES. _CategoryProductGrid paged within one category and set
/// hasNext=false when that category ran out, after which loadMore() returned
/// early forever. Nothing was broken - the screen simply had no concept of a
/// next category.
class CategoryFeedController extends ChangeNotifier {
  CategoryFeedController({
    required this.anchorCategory,
    required List<Category> allCategories,
    required CategoryPageFetcher fetchPage,
    this.pageSize = 20,
  })  : _allCategories = allCategories,
        _fetchPage = fetchPage;

  /// The category the customer actually opened. Always shown first.
  final Category anchorCategory;

  final List<Category> _allCategories;
  final CategoryPageFetcher _fetchPage;
  final int pageSize;

  final List<CategoryFeedSection> _sections = [];
  List<Category> _order = const [];

  /// Categories already appended, so a duplicate id in the source list cannot
  /// produce the same section twice.
  final Set<int> _loadedCategoryIds = {};

  /// Every product id on screen. A product genuinely filed under two
  /// categories would otherwise appear twice as the feed rolls on, and a
  /// duplicate key in a lazy list is a hard crash, not a cosmetic issue.
  final Set<int> _seenProductIds = {};

  int _nextCategoryIndex = 0;
  bool _isInitialLoading = true;
  bool _isLoadingMore = false;
  bool _reachedEnd = false;
  String? _errorMessage;

  /// Guards against a scroll firing advance() faster than requests return.
  bool _busy = false;

  /// Bumped on every reset. A response carrying a stale generation is dropped
  /// rather than appended to a feed the customer has already moved on from.
  int _generation = 0;

  List<CategoryFeedSection> get sections => List.unmodifiable(_sections);
  bool get isInitialLoading => _isInitialLoading;
  bool get isLoadingMore => _isLoadingMore;
  bool get reachedEnd => _reachedEnd;
  String? get errorMessage => _errorMessage;
  bool get isEmpty => _sections.every((s) => s.products.isEmpty);

  int get loadedProductCount =>
      _sections.fold(0, (sum, section) => sum + section.products.length);

  /// The category a given flat product index belongs to - what the sidebar
  /// highlight reads.
  Category? categoryAtProductIndex(int index) {
    var offset = 0;
    for (final section in _sections) {
      if (index < offset + section.products.length) return section.category;
      offset += section.products.length;
    }
    return _sections.isEmpty ? null : _sections.last.category;
  }

  /// Where a category's first product sits in the flat list, for "tap the
  /// sidebar and jump there". Null when that category is not loaded yet.
  int? productIndexOfCategory(int categoryId) {
    var offset = 0;
    for (final section in _sections) {
      if (section.category.id == categoryId) return offset;
      offset += section.products.length;
    }
    return null;
  }

  Future<void> start() async {
    _isInitialLoading = true;
    _errorMessage = null;
    notifyListeners();
    await _resetAndLoad();
  }

  Future<void> retry() => _resetAndLoad();

  Future<void> _resetAndLoad() async {
    final generation = ++_generation;

    _sections.clear();
    _loadedCategoryIds.clear();
    _seenProductIds.clear();
    _nextCategoryIndex = 0;
    _reachedEnd = false;
    _errorMessage = null;
    _busy = false;

    _order = _buildOrder(_allCategories);

    await _loadNextChunk(generation);
    if (generation != _generation) return;

    _isInitialLoading = false;
    notifyListeners();
  }

  /// Anchor first, then the rest in rail order, wrapping past the end.
  ///
  /// Wrapping is what makes every category reachable from any entry point:
  /// opening the LAST category in the rail would otherwise end the feed
  /// immediately, and "scroll through everything" has to mean everything, not
  /// just the categories that happen to sit below wherever the customer
  /// started.
  List<Category> _buildOrder(List<Category> all) {
    final index = all.indexWhere((c) => c.id == anchorCategory.id);
    if (index < 0) {
      // The anchor is not in the rail - a stale cached list, or it was just
      // deactivated. Show it first regardless; the customer asked for it.
      return [anchorCategory, ...all];
    }
    return [...all.sublist(index), ...all.sublist(0, index)];
  }

  /// Another page of the current category, or the first page of the next one.
  ///
  /// Safe to call on every scroll frame: it no-ops while busy and once the
  /// catalogue is exhausted, which is what stops fast scrolling from firing a
  /// burst of requests.
  Future<void> advance() async {
    if (_busy || _reachedEnd || _isInitialLoading) return;
    await _loadNextChunk(_generation);
    notifyListeners();
  }

  Future<void> _loadNextChunk(int generation) async {
    if (_busy) return;
    _busy = true;
    // Cleared on every attempt: a retry that succeeds must not leave the
    // previous failure's message sitting under the last row.
    _errorMessage = null;
    _isLoadingMore = _sections.isNotEmpty;
    if (_isLoadingMore) notifyListeners();

    try {
      final current = _sections.isEmpty ? null : _sections.last;
      if (current != null && current.hasMorePages) {
        await _loadMorePagesOf(_sections.length - 1, generation);
      } else {
        await _appendNextCategory(generation);
      }
    } catch (e) {
      if (generation != _generation) return;
      // Whatever is already on screen stays. A customer who has scrolled
      // through four categories must not lose them because the fifth failed.
      _errorMessage = _describe(e);
    } finally {
      if (generation == _generation) {
        _busy = false;
        _isLoadingMore = false;
      }
    }
  }

  Future<void> _loadMorePagesOf(int sectionIndex, int generation) async {
    final section = _sections[sectionIndex];
    final fetched = await _fetchPage(
      categoryId: section.category.id,
      page: section.nextPage,
      size: pageSize,
    );
    if (generation != _generation) return;

    final fresh = <Product>[];
    for (final product in fetched) {
      if (_seenProductIds.add(product.id)) fresh.add(product);
    }

    _sections[sectionIndex] = section.copyWith(
      products: [...section.products, ...fresh],
      nextPage: section.nextPage + 1,
      // A short page means the end of this category. browseByCategory returns
      // a bare list, so page length is the only signal available.
      hasMorePages: fetched.length >= pageSize,
    );
  }

  Future<void> _appendNextCategory(int generation) async {
    while (_nextCategoryIndex < _order.length &&
        _loadedCategoryIds.contains(_order[_nextCategoryIndex].id)) {
      _nextCategoryIndex++;
    }

    if (_nextCategoryIndex >= _order.length) {
      // THE ONLY PLACE THE FEED ENDS - when every category has been shown,
      // never because one category ran out of products.
      _reachedEnd = true;
      return;
    }

    // A category can legitimately be empty. Appending it would put a header
    // over dead space, so it is skipped - but bounded, because a run of empty
    // categories must not turn one scroll into a dozen chained requests. The
    // rest are picked up by the next advance() a moment later.
    const maxEmptyPerAdvance = 5;

    for (var attempt = 0; attempt < maxEmptyPerAdvance; attempt++) {
      if (_nextCategoryIndex >= _order.length) {
        _reachedEnd = true;
        return;
      }

      final category = _order[_nextCategoryIndex];
      final fetched = await _fetchPage(
        categoryId: category.id,
        page: 0,
        size: pageSize,
      );
      if (generation != _generation) return;

      _nextCategoryIndex++;
      _loadedCategoryIds.add(category.id);

      final fresh = <Product>[];
      for (final product in fetched) {
        if (_seenProductIds.add(product.id)) fresh.add(product);
      }

      if (fresh.isEmpty) continue; // empty category - skip, keep going

      _sections.add(CategoryFeedSection(
        category: category,
        products: fresh,
        nextPage: 1,
        hasMorePages: fetched.length >= pageSize,
      ));
      return;
    }
  }

  String _describe(Object error) {
    appLog('Category feed page failed: $error');
    return 'Could not load more products. Check your connection and try again.';
  }
}
