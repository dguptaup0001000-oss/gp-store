// show, not a bare import: foundation also exports a Category annotation
// class that would collide with the domain model used all over this file.
import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/search/search_debouncer.dart';
import '../../../core/util/app_haptics.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/cart_aware_product_card.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/product_card.dart';
import '../../../shared/widgets/scroll_to_top.dart';
import '../../../shared/widgets/product_page_route.dart';
import '../domain/product_models.dart';
import 'category_feed_controller.dart';
import 'product_detail_screen.dart';
import 'products_providers.dart';
import '../../../core/images/gp_network_image.dart';

/// Category browsing with a persistent left rail.
///
/// Replaces a screen that opened one category and showed a flat grid: to
/// reach a different category you had to go back and start again. The rail
/// keeps every category one tap away while the grid stays put, which is the
/// shape Indian grocery shoppers already expect.
///
/// The visual language is GP-Store's own rather than borrowed: the selected
/// rail item is marked by a cobalt bar and a tinted panel that merges into
/// the grid's background, and unselected tiles sit on rotating soft washes
/// (peach, cream, mist, lavender) so the rail reads as a colourful index
/// rather than a grey list.
class CategoryBrowseScreen extends ConsumerStatefulWidget {
  const CategoryBrowseScreen({super.key, required this.initialCategory});

  final Category initialCategory;

  @override
  ConsumerState<CategoryBrowseScreen> createState() => _CategoryBrowseScreenState();
}

class _CategoryBrowseScreenState extends ConsumerState<CategoryBrowseScreen> {
  /// The category the feed is BUILT FROM. Changes only on a rail tap (or when
  /// a search starts), never from scrolling.
  ///
  /// Kept separate from [_visible] on purpose. The rail highlight now follows
  /// the scroll, and if the highlight also drove the feed's key then scrolling
  /// into the next category would rebuild the feed anchored there, which would
  /// scroll it back to the top, which would change the highlight again - a
  /// loop. One field is written by taps, the other by scrolling, and only the
  /// tap-written one is allowed to reset anything.
  late Category _anchor;

  /// The category the customer is LOOKING at. Drives the rail highlight, the
  /// app bar title and the search hint.
  ///
  /// A ValueNotifier rather than setState because it is written on scroll:
  /// setState here would rebuild the entire product feed on every scroll
  /// frame. Only the rail, the title and the hint subscribe. Same reasoning as
  /// ScrollToTop, which was written for exactly this problem.
  late final ValueNotifier<Category> _visible;

  /// Bumped on every rail tap so that tapping a category the feed is already
  /// anchored to still rebuilds it - otherwise the widget key would be
  /// unchanged and the tap would do nothing, which is precisely what happens
  /// once the highlight can drift away from the anchor by scrolling.
  int _resetToken = 0;

  /// Search text. Applied to the category being viewed when the customer
  /// started typing.
  ///
  /// Held here rather than inside the grid so that switching category keeps
  /// the customer's search - they are narrowing a shelf, not starting over.
  String _keyword = '';

  @override
  void initState() {
    super.initState();
    _anchor = widget.initialCategory;
    _visible = ValueNotifier<Category>(widget.initialCategory);
  }

  @override
  void dispose() {
    _visible.dispose();
    super.dispose();
  }

  void _onSearchChanged(String value, Category viewing) {
    if (value == _keyword) return;
    setState(() {
      _keyword = value;
      // Search narrows the shelf the customer is LOOKING at, not the one they
      // happened to enter from. Now that the feed rolls on past the category
      // it was opened with, "Search in Snacks" has to search Snacks even when
      // the customer arrived through Dairy - otherwise the hint is a lie, the
      // same lie this box used to tell when it opened global search.
      _anchor = viewing;
    });
  }

  @override
  Widget build(BuildContext context) {
    final categoriesAsync = ref.watch(categoriesProvider);

    return Scaffold(
      appBar: AppBar(
        title: ValueListenableBuilder<Category>(
          valueListenable: _visible,
          builder: (context, category, _) => Text(category.name),
        ),
      ),
      bottomNavigationBar: const CartSummaryBar(),
      body: Column(
        children: [
          ValueListenableBuilder<Category>(
            valueListenable: _visible,
            builder: (context, category, _) => _SearchBar(
              categoryName: category.name,
              onChanged: (value) => _onSearchChanged(value, category),
            ),
          ),
          Expanded(
            child: categoriesAsync.when(
              loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
              error: (e, _) => _Message(
                icon: Icons.wifi_off_rounded,
                text: 'Could not load categories',
                action: 'Try again',
                onAction: () => ref.invalidate(categoriesProvider),
              ),
              data: (categories) {
                final list = categories.where((c) => c.active).toList();
                if (list.isEmpty) {
                  return const _Message(icon: Icons.category_outlined, text: 'No categories yet');
                }
                return Row(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _CategoryRail(
                      categories: list,
                      visible: _visible,
                      onSelect: (category) {
                        // The rail was the one navigation surface in this
                        // screen with no physical response at all - every
                        // other list in the app buzzes on selection.
                        AppHaptics.selection();
                        // Set immediately so the highlight moves with the
                        // finger rather than waiting for the first page of
                        // the new feed to arrive.
                        _visible.value = category;
                        setState(() {
                          _anchor = category;
                          _resetToken++;
                        });
                      },
                    ),
                    Expanded(
                      // TWO MODES, ONE SCREEN.
                      //
                      // Browsing rolls continuously through every category.
                      // Searching stays inside the one shelf the customer
                      // aimed at - rolling a search into unrelated categories
                      // would answer a question nobody asked.
                      //
                      // Both are keyed so that changing anchor, keyword or
                      // reset token gets FRESH paging state, rather than the
                      // previous results lingering while the new page loads.
                      child: _keyword.isEmpty
                          ? _ContinuousCategoryFeed(
                              key: ValueKey<String>('feed:${_anchor.id}:$_resetToken'),
                              anchor: _anchor,
                              categories: list,
                              onVisibleCategoryChanged: (category) =>
                                  _visible.value = category,
                            )
                          : _CategoryProductGrid(
                              key: ValueKey<String>('search:${_anchor.id}:$_keyword'),
                              category: _anchor,
                              keyword: _keyword,
                            ),
                    ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

/// Search within the selected category.
///
/// A real input, not a button. It used to push the GLOBAL search screen,
/// which meant the label "Search in Masala & Spices" was untrue - the
/// customer typed into what looked like a category filter and got results
/// from the whole shop.
///
/// Debounced at 400ms, the same interval the other search fields use, so a
/// five-letter word costs one request rather than five.
class _SearchBar extends StatefulWidget {
  const _SearchBar({required this.categoryName, required this.onChanged});

  final String categoryName;
  final ValueChanged<String> onChanged;

  @override
  State<_SearchBar> createState() => _SearchBarState();
}

class _SearchBarState extends State<_SearchBar> {
  final _controller = TextEditingController();

  /// The shared rule rather than a third copy of it - see SearchDebouncer,
  /// which was written because this screen, brand browse and global search
  /// each carried their own Timer and their own 400ms literal. The minimum
  /// length comes with it, so typing one character no longer sends the
  /// backend looking for it.
  final _search = SearchDebouncer();

  @override
  void dispose() {
    _search.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _onChanged(String value) {
    // The token goes unused: this screen hands a keyword to its parent
    // rather than making the request itself, so there is nothing here to
    // abort. Debounce and minimum length are the whole benefit.
    _search.onQueryChanged(
      value,
      onSearch: (query, _) => widget.onChanged(query),
      onCleared: () => widget.onChanged(''),
    );
  }

  void _clear() {
    _controller.clear();
    // An empty term cancels the pending debounce and reports the clear
    // immediately - there is nothing to wait for, and leaving results on
    // screen after the box is empty reads as the app being stuck.
    _search.onQueryChanged(
      '',
      onSearch: (query, _) => widget.onChanged(query),
      onCleared: () => widget.onChanged(''),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.cardBackground,
          // Fully rounded and lifted, matching the home search pill, so the
          // two read as the same control in the same app.
          borderRadius: BorderRadius.circular(28),
          boxShadow: AppElevation.card,
        ),
        child: TextField(
          controller: _controller,
          onChanged: _onChanged,
          textInputAction: TextInputAction.search,
          style: const TextStyle(fontSize: 14, color: AppColors.textPrimary),
          decoration: InputDecoration(
            isDense: true,
            // The shared InputDecorationTheme fills and rounds every field;
            // here the Container above already does both, so the field itself
            // stays transparent and borderless rather than drawing a second
            // shape inside the first.
            filled: false,
            border: InputBorder.none,
            enabledBorder: InputBorder.none,
            focusedBorder: InputBorder.none,
            contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            prefixIcon: const Icon(Icons.search, color: AppColors.textSecondary, size: 20),
            hintText: 'Search in ${widget.categoryName}',
            hintStyle: const TextStyle(color: AppColors.textSecondary, fontSize: 14),
            suffixIcon: ValueListenableBuilder<TextEditingValue>(
              valueListenable: _controller,
              builder: (context, value, child) {
                // Only present when there is something to clear, so the field
                // is not permanently carrying a dead button.
                if (value.text.isEmpty) return const SizedBox.shrink();
                return IconButton(
                  icon: const Icon(Icons.close, size: 18, color: AppColors.textSecondary),
                  onPressed: _clear,
                  tooltip: 'Clear search',
                );
              },
            ),
          ),
        ),
      ),
    );
  }
}

/// The left rail. Scrolls independently of the grid.
///
/// The highlight is driven by a ValueListenable rather than a plain field
/// because it now moves while the customer scrolls the feed, not only when
/// they tap. Subscribing here keeps those updates from rebuilding the product
/// feed alongside the rail.
class _CategoryRail extends StatefulWidget {
  const _CategoryRail({
    required this.categories,
    required this.visible,
    required this.onSelect,
  });

  final List<Category> categories;

  /// The category currently on screen in the feed.
  final ValueListenable<Category> visible;

  final ValueChanged<Category> onSelect;

  @override
  State<_CategoryRail> createState() => _CategoryRailState();
}

class _CategoryRailState extends State<_CategoryRail> {
  /// Soft washes cycled across tiles so the rail looks like an index rather
  /// than a grey list. Fixed order, indexed by position, so a category keeps
  /// the same colour every time the screen opens instead of flickering to a
  /// new one on each build.
  static const _washes = [AppColors.mist, AppColors.peach, AppColors.cream];

  final _scrollController = ScrollController();

  /// One key per category so the highlighted tile can be scrolled into view.
  /// Keyed by id rather than index so it survives the list being refreshed.
  final Map<int, GlobalKey> _tileKeys = {};

  int? _lastRevealed;

  @override
  void initState() {
    super.initState();
    widget.visible.addListener(_reveal);
  }

  @override
  void dispose() {
    widget.visible.removeListener(_reveal);
    _scrollController.dispose();
    super.dispose();
  }

  /// Keeps the highlighted tile on screen.
  ///
  /// Without this the highlight is correct but invisible: scrolling from the
  /// first category into the tenth marks a tile the customer would have to
  /// scroll the rail to find, which reads as the rail being broken.
  void _reveal() {
    final id = widget.visible.value.id;
    if (id == _lastRevealed) return;
    _lastRevealed = id;

    // After the frame: the tile for a category that just became visible may
    // not be laid out yet, and ensureVisible needs a real render box.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final tileContext = _tileKeys[id]?.currentContext;
      // Null for a tile far outside the rail's build range. Nothing to do -
      // the highlight is still correct, it is simply off screen, and forcing
      // a jump would need a layout guess this widget does not have.
      if (tileContext == null) return;
      Scrollable.ensureVisible(
        tileContext,
        alignment: 0.5,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOut,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    // Proportional, not a fixed 88px: on a small phone a fixed rail eats the
    // grid, and on a large one it leaves the grid stranded. Clamped so the
    // labels stay readable at both extremes.
    final railWidth = (MediaQuery.of(context).size.width * 0.24).clamp(84.0, 120.0);

    return Container(
      width: railWidth,
      color: AppColors.surfaceSoft,
      child: ValueListenableBuilder<Category>(
        valueListenable: widget.visible,
        builder: (context, selected, _) => ListView.builder(
          controller: _scrollController,
          padding: const EdgeInsets.symmetric(vertical: 8),
          itemCount: widget.categories.length,
          itemBuilder: (context, index) {
            final category = widget.categories[index];
            final isSelected = category.id == selected.id;
            final tileKey = _tileKeys.putIfAbsent(category.id, () => GlobalKey());

            return InkWell(
              key: tileKey,
              onTap: () => widget.onSelect(category),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 6),
                decoration: BoxDecoration(
                  // The selected tile takes the grid's white so the two read as
                  // one surface - the rail visually "opens into" the products.
                  color: isSelected ? AppColors.cardBackground : Colors.transparent,
                  border: Border(
                    left: BorderSide(
                      color: isSelected ? AppColors.primary : Colors.transparent,
                      width: 3,
                    ),
                  ),
                ),
                child: Column(
                  children: [
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 160),
                      curve: Curves.easeOut,
                      height: 44,
                      width: 44,
                      decoration: BoxDecoration(
                        color: isSelected
                            ? AppColors.tint(AppColors.primary)
                            : _washes[index % _washes.length],
                        borderRadius: BorderRadius.circular(AppRadius.md),
                        // The selected tile lifts; the others sit flat against
                        // the rail. Depth carries the selection state alongside
                        // the cobalt bar, so it is legible at a glance without
                        // adding another colour.
                        boxShadow: isSelected ? AppElevation.tile : null,
                      ),
                      clipBehavior: Clip.antiAlias,
                      // A broken category image must not blank the rail and
                      // strand the customer with no way to navigate - which
                      // is exactly what GpNetworkImage guarantees, here and
                      // on every other surface.
                      child: GpNetworkImage(
                        url: category.imageUrl,
                        renderWidth: 44,
                        fit: BoxFit.cover,
                        fallbackIcon: Icons.category_outlined,
                        fallbackIconSize: 20,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      category.name,
                      textAlign: TextAlign.center,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 11,
                        height: 1.2,
                        fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
                        color: isSelected ? AppColors.primary : AppColors.textPrimary,
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// The continuous browse feed: category after category, without stopping.
///
/// WHAT WAS WRONG. _CategoryProductGrid (still below, still used for search)
/// pages within ONE category and sets hasNext=false when that category runs
/// out, after which loadMore() returns early forever. Nothing threw and
/// nothing logged - the screen simply had no concept of a next category, so
/// the scroll ended at the bottom of whichever shelf you opened.
///
/// The ordering, paging, empty-category skipping and wrap-around all live in
/// CategoryFeedController, which is unit tested without a network. This widget
/// only turns its sections into slivers and reports which one is on screen.
class _ContinuousCategoryFeed extends ConsumerStatefulWidget {
  const _ContinuousCategoryFeed({
    super.key,
    required this.anchor,
    required this.categories,
    required this.onVisibleCategoryChanged,
  });

  /// The category the customer opened. Shown first; the feed rolls on from
  /// there and wraps past the end of the rail.
  final Category anchor;

  final List<Category> categories;

  /// Fired when the section under the top of the viewport changes, so the rail
  /// highlight can follow the scroll.
  final ValueChanged<Category> onVisibleCategoryChanged;

  @override
  ConsumerState<_ContinuousCategoryFeed> createState() => _ContinuousCategoryFeedState();
}

class _ContinuousCategoryFeedState extends ConsumerState<_ContinuousCategoryFeed> {
  late final CategoryFeedController _controller;

  /// Marks the top of the viewport in global coordinates. Section headers are
  /// compared against it to work out which one is currently pinned.
  final GlobalKey _feedKey = GlobalKey();

  final Map<int, GlobalKey> _headerKeys = {};

  /// Last category reported upwards, so a scroll that stays inside one section
  /// does not fire a notification on every frame.
  int? _lastReportedCategoryId;

  @override
  void initState() {
    super.initState();
    _controller = CategoryFeedController(
      anchorCategory: widget.anchor,
      allCategories: widget.categories,
      fetchPage: ({required categoryId, required page, required size}) => ref
          .read(productsRepositoryProvider)
          .browseByCategory(categoryId, page: page, size: size),
    );
    _controller.start();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  bool _onScroll(ScrollNotification notification) {
    // Same trigger distance as every other paginated list in the app. The
    // controller no-ops while a request is in flight and once the whole
    // catalogue has been shown, so firing this on each frame is safe.
    if (notification.metrics.extentAfter < 400) _controller.advance();
    _syncVisibleCategory();
    return false;
  }

  /// Works out which section is under the top of the viewport.
  ///
  /// Read from the laid-out headers rather than computed from the scroll
  /// offset: the grid's row height depends on the card aspect ratio, which
  /// depends on the text scale, so any arithmetic here would be a guess that
  /// drifts. A pinned header sits exactly at the viewport top, the ones below
  /// it sit lower, and the ones above have been disposed - so the LAST header
  /// at or above the top is the section being viewed.
  void _syncVisibleCategory() {
    final feedBox = _feedKey.currentContext?.findRenderObject() as RenderBox?;
    if (feedBox == null || !feedBox.hasSize) return;
    final viewportTop = feedBox.localToGlobal(Offset.zero).dy;

    Category? active;
    for (final section in _controller.sections) {
      final headerContext = _headerKeys[section.category.id]?.currentContext;
      if (headerContext == null) continue;
      final headerBox = headerContext.findRenderObject() as RenderBox?;
      if (headerBox == null || !headerBox.hasSize) continue;
      // A hair of tolerance: the pinned header lands on the viewport top to
      // sub-pixel precision, not exactly on it.
      if (headerBox.localToGlobal(Offset.zero).dy <= viewportTop + 1.0) {
        active = section.category;
      }
    }

    if (active == null || active.id == _lastReportedCategoryId) return;
    _lastReportedCategoryId = active.id;
    widget.onVisibleCategoryChanged(active);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _controller,
      builder: (context, _) {
        if (_controller.isInitialLoading) {
          return const Center(child: CircularProgressIndicator(strokeWidth: 2));
        }

        if (_controller.sections.isEmpty && _controller.errorMessage != null) {
          return _Message(
            icon: Icons.wifi_off_rounded,
            text: 'Could not load products',
            action: 'Try again',
            onAction: _controller.retry,
          );
        }

        if (_controller.sections.isEmpty) {
          return const _Message(
            icon: Icons.inventory_2_outlined,
            text: 'No products available here yet.',
          );
        }

        return Container(
          key: _feedKey,
          // The lavender ground, not a cream panel. Home is lavender with cream
          // cards lifted off it; a solid cream grid here would make the category
          // page read as a different app, which is precisely what the design
          // brief rules out.
          color: AppColors.background,
          // Wraps the feed rather than the whole screen, so the pill is centred
          // over the products rather than over the rail as well.
          child: ScrollToTop(
            builder: (context, scrollController) => NotificationListener<ScrollNotification>(
              onNotification: _onScroll,
              child: CustomScrollView(
                controller: scrollController,
                slivers: _buildSlivers(context),
              ),
            ),
          ),
        );
      },
    );
  }

  List<Widget> _buildSlivers(BuildContext context) {
    final slivers = <Widget>[];

    for (final section in _controller.sections) {
      final headerKey = _headerKeys.putIfAbsent(section.category.id, () => GlobalKey());

      slivers.add(
        SliverPersistentHeader(
          pinned: true,
          delegate: _SectionHeaderDelegate(
            title: section.category.name,
            headerKey: headerKey,
          ),
        ),
      );

      slivers.add(
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(10, 4, 10, 14),
          sliver: SliverGrid(
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              mainAxisSpacing: 10,
              crossAxisSpacing: 10,
              // The rail takes horizontal space, so the cards here are narrower
              // than on a full-width grid - the ratio is told about that rather
              // than assuming the whole screen.
              childAspectRatio: ProductGrid.aspectRatio(context, columns: 2),
            ),
            delegate: SliverChildBuilderDelegate(
              (context, index) {
                final product = section.products[index];
                return CartAwareProductCard(
                  key: ValueKey<int>(product.id),
                  product: product,
                  // ProductCard already fires a selection tick on tap; adding
                  // one here would buzz twice for one finger.
                  //
                  // Scale-and-fade rather than the default slide - see
                  // productPageRoute for why this is not a hero flight.
                  onTap: () => Navigator.of(context).push(
                    productPageRoute(ProductDetailScreen(product: product)),
                  ),
                );
              },
              childCount: section.products.length,
            ),
          ),
        ),
      );
    }

    slivers.add(SliverToBoxAdapter(child: _footer()));
    return slivers;
  }

  Widget _footer() {
    if (_controller.errorMessage != null) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(24, 8, 24, 28),
        child: Column(
          children: [
            Text(
              _controller.errorMessage!,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textSecondary, fontSize: 12),
            ),
            const SizedBox(height: 10),
            OutlinedButton(
              onPressed: () {
                AppHaptics.action();
                _controller.advance();
              },
              child: const Text('Try again'),
            ),
          ],
        ),
      );
    }

    if (_controller.reachedEnd) {
      return const Padding(
        padding: EdgeInsets.fromLTRB(24, 4, 24, 28),
        child: Center(
          child: Text(
            "That's every category.",
            style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
          ),
        ),
      );
    }

    return const Padding(
      padding: EdgeInsets.fromLTRB(24, 8, 24, 28),
      child: Center(
        child: SizedBox(height: 22, width: 22, child: CircularProgressIndicator(strokeWidth: 2)),
      ),
    );
  }
}

/// The small sticky label that names the shelf you are currently looking at.
///
/// Pinned, so once the feed rolls past the category the customer opened there
/// is always something on screen saying which one these products belong to.
/// Deliberately small and quiet - it is a signpost inside an existing screen,
/// not a new section design.
class _SectionHeaderDelegate extends SliverPersistentHeaderDelegate {
  _SectionHeaderDelegate({required this.title, required this.headerKey});

  final String title;

  /// Lets the feed find this header's position to work out which section is on
  /// screen. Owned by the feed, not created here, so it survives the delegate
  /// being rebuilt.
  final GlobalKey headerKey;

  static const double _height = 34;

  @override
  double get minExtent => _height;

  @override
  double get maxExtent => _height;

  @override
  Widget build(BuildContext context, double shrinkOffset, bool overlapsContent) {
    return Container(
      key: headerKey,
      height: _height,
      alignment: Alignment.centerLeft,
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 0),
      // Opaque on the feed's own ground: a pinned header floats OVER the grid,
      // so a transparent one would have product cards sliding through the text.
      color: AppColors.background,
      child: Row(
        children: [
          Container(
            width: 3,
            height: 14,
            decoration: BoxDecoration(
              color: AppColors.primary,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.2,
                color: AppColors.textPrimary,
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  bool shouldRebuild(covariant _SectionHeaderDelegate oldDelegate) =>
      oldDelegate.title != title || oldDelegate.headerKey != headerKey;
}

/// Paginated 2-column grid for ONE category's search results.
///
/// Still the browse path's shape, but no longer the browse path: browsing
/// goes through _ContinuousCategoryFeed above. This stays because a search
/// is a different question - the customer asked about one shelf, and rolling
/// the results on into unrelated categories would answer a question nobody
/// asked. It also uses browseByCategoryFiltered, which reports totalPages,
/// rather than inferring the end from page length.
class _CategoryProductGrid extends ConsumerStatefulWidget {
  const _CategoryProductGrid({super.key, required this.category, required this.keyword})
      // Not a default of '': an empty keyword means "browse", and browsing is
      // _ContinuousCategoryFeed's job now. Letting one through here would
      // silently reinstate the stops-at-the-end-of-the-category behaviour
      // this screen was changed to remove.
      : assert(keyword != '', 'Browsing goes through _ContinuousCategoryFeed');

  final Category category;

  /// Narrows the grid to this category's matching products. Never empty.
  final String keyword;

  @override
  ConsumerState<_CategoryProductGrid> createState() => _CategoryProductGridState();
}

class _CategoryProductGridState extends ConsumerState<_CategoryProductGrid> {
  static const _pageSize = 20;

  final List<Product> _products = [];
  final Set<int> _seenIds = <int>{};

  int _nextPage = 0;
  bool _hasNext = true;
  bool _isLoading = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _loadMore();
  }

  Future<void> _loadMore() async {
    // Same three guards as the home feed: no concurrent fetch, stop when the
    // server says there is no next page, and never append a product twice.
    if (_isLoading || !_hasNext) return;
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final repository = ref.read(productsRepositoryProvider);

      // browseByCategoryFiltered is what makes "Search in {category}" actually
      // search inside the category - the box used to open GLOBAL search, so
      // the label was a lie and results came back from shelves the customer
      // had not asked about.
      final result = await repository.browseByCategoryFiltered(
        categoryId: widget.category.id,
        keyword: widget.keyword,
        page: _nextPage,
        size: _pageSize,
      );
      final fetched = result.products;
      // This endpoint reports totalPages, which is a real answer rather than
      // an inference from page length.
      final serverSaysMore = _nextPage + 1 < result.totalPages;

      // The widget is keyed by category id, so a stale response from a
      // previous category cannot land here - that widget is already gone.
      // This guards the ordinary case of the screen being popped mid-request.
      if (!mounted) return;

      setState(() {
        for (final product in fetched) {
          if (_seenIds.add(product.id)) _products.add(product);
        }
        _nextPage += 1;
        _hasNext = serverSaysMore;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _error = e;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_products.isEmpty && _isLoading) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }

    if (_products.isEmpty && _error != null) {
      return _Message(
        icon: Icons.wifi_off_rounded,
        text: 'Could not load products',
        action: 'Try again',
        onAction: _loadMore,
      );
    }

    if (_products.isEmpty) {
      return _Message(
        icon: Icons.search_off_rounded,
        text: 'Nothing in ${widget.category.name} matches "${widget.keyword}".',
      );
    }

    return Container(
      // The lavender ground, not a cream panel. Home is lavender with cream
      // cards lifted off it; a solid cream grid here would make the category
      // page read as a different app, which is precisely what the design
      // brief rules out.
      color: AppColors.background,
      // Wraps the grid rather than the whole screen, so the pill is centred
      // over the products rather than over the rail as well - and so that
      // switching category, which replaces this keyed widget, gets a fresh
      // button state at the new grid's offset zero.
      child: ScrollToTop(
        builder: (context, scrollController) => NotificationListener<ScrollNotification>(
        onNotification: (notification) {
          if (notification.metrics.extentAfter < 400) _loadMore();
          return false;
        },
        child: GridView.builder(
          controller: scrollController,
          padding: const EdgeInsets.all(10),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 2,
            mainAxisSpacing: 10,
            crossAxisSpacing: 10,
            // The rail takes horizontal space, so the cards here are narrower
            // than on a full-width grid - the ratio is told about that rather
            // than assuming the whole screen.
            childAspectRatio: ProductGrid.aspectRatio(
              context,
              columns: 2,
            ),
          ),
          itemCount: _products.length + (_isLoading || _error != null ? 1 : 0),
          itemBuilder: (context, index) {
            if (index >= _products.length) {
              return Center(
                child: _error != null
                    ? IconButton(
                        icon: const Icon(Icons.refresh, color: AppColors.primary),
                        onPressed: _loadMore,
                      )
                    : const SizedBox(
                        height: 22,
                        width: 22,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
              );
            }

            final product = _products[index];
            return CartAwareProductCard(
              key: ValueKey<int>(product.id),
              product: product,
              // Scale-and-fade rather than the default slide - see
              // productPageRoute for why this is not a hero flight.
              onTap: () => Navigator.of(context).push(
                productPageRoute(ProductDetailScreen(product: product)),
              ),
            );
          },
        ),
        ),
      ),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.icon, required this.text, this.action, this.onAction});

  final IconData icon;
  final String text;
  final String? action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 36, color: AppColors.textSecondary),
            const SizedBox(height: 12),
            Text(text, textAlign: TextAlign.center,
                style: const TextStyle(color: AppColors.textSecondary)),
            if (action != null) ...[
              const SizedBox(height: 12),
              OutlinedButton(onPressed: onAction, child: Text(action!)),
            ],
          ],
        ),
      ),
    );
  }
}
