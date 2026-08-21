import 'dart:async';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/cart_aware_product_card.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/product_card.dart';
import '../../../shared/widgets/scroll_to_top.dart';
import '../../../shared/widgets/product_page_route.dart';
import '../domain/product_models.dart';
import 'product_detail_screen.dart';
import 'products_providers.dart';

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
  late Category _selected;

  @override
  void initState() {
    super.initState();
    _selected = widget.initialCategory;
  }

  /// Search text, applied to the SELECTED category only.
  ///
  /// Held here rather than inside the grid so that switching category keeps
  /// the customer's search - they are narrowing a shelf, not starting over.
  String _keyword = '';

  @override
  Widget build(BuildContext context) {
    final categoriesAsync = ref.watch(categoriesProvider);

    return Scaffold(
      appBar: AppBar(title: Text(_selected.name)),
      bottomNavigationBar: const CartSummaryBar(),
      body: Column(
        children: [
          _SearchBar(
            categoryName: _selected.name,
            onChanged: (value) {
              if (value == _keyword) return;
              setState(() => _keyword = value);
            },
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
                      selectedId: _selected.id,
                      // Only the selection changes - the grid rebuilds for the
                      // new category, the rail does not reload, and the screen
                      // is never rebuilt from scratch.
                      onSelect: (category) => setState(() => _selected = category),
                    ),
                    Expanded(
                      child: _CategoryProductGrid(
                        // Keyed by category id so switching categories gets a
                        // FRESH grid with its own paging state, rather than the
                        // previous category's products lingering while the new
                        // page loads.
                        // Keyed by category AND keyword. Reusing the existing
                        // key-to-reset mechanism means a new search gets fresh
                        // paging state for free, with no extra reset logic to
                        // get wrong.
                        key: ValueKey<String>('${_selected.id}:$_keyword'),
                        category: _selected,
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
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _onChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () {
      widget.onChanged(value.trim());
    });
  }

  void _clear() {
    _debounce?.cancel();
    _controller.clear();
    widget.onChanged('');
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
class _CategoryRail extends StatelessWidget {
  const _CategoryRail({
    required this.categories,
    required this.selectedId,
    required this.onSelect,
  });

  final List<Category> categories;
  final int selectedId;
  final ValueChanged<Category> onSelect;

  /// Soft washes cycled across tiles so the rail looks like an index rather
  /// than a grey list. Fixed order, indexed by position, so a category keeps
  /// the same colour every time the screen opens instead of flickering to a
  /// new one on each build.
  static const _washes = [AppColors.mist, AppColors.peach, AppColors.cream];

  @override
  Widget build(BuildContext context) {
    // Proportional, not a fixed 88px: on a small phone a fixed rail eats the
    // grid, and on a large one it leaves the grid stranded. Clamped so the
    // labels stay readable at both extremes.
    final railWidth = (MediaQuery.of(context).size.width * 0.24).clamp(84.0, 120.0);

    return Container(
      width: railWidth,
      color: AppColors.surfaceSoft,
      child: ListView.builder(
        padding: const EdgeInsets.symmetric(vertical: 8),
        itemCount: categories.length,
        itemBuilder: (context, index) {
          final category = categories[index];
          final isSelected = category.id == selectedId;

          return InkWell(
            onTap: () => onSelect(category),
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
                    child: category.imageUrl == null
                        ? Icon(
                            Icons.category_outlined,
                            size: 20,
                            color: isSelected ? AppColors.primary : AppColors.textSecondary,
                          )
                        : CachedNetworkImage(
                            imageUrl: category.imageUrl!,
                            fit: BoxFit.cover,
                            // Cached, not Image.network: the rail rebuilds on
                            // every category tap, and an uncached image
                            // re-fetches the whole list from the network each
                            // time somebody browses across categories.
                            //
                            // Decoded at 132px (44 logical px x 3 for the
                            // densest phones) rather than at whatever size the
                            // original happens to be - a rail of full-size
                            // category photos is real memory pressure for
                            // tiles this small.
                            memCacheWidth: 132,
                            // A broken category image must not blank the rail
                            // and strand the customer with no navigation.
                            errorWidget: (_, __, ___) => Icon(
                              Icons.category_outlined,
                              size: 20,
                              color: isSelected ? AppColors.primary : AppColors.textSecondary,
                            ),
                            placeholder: (_, __) => const SizedBox.shrink(),
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
    );
  }
}

/// Paginated 2-column grid for the selected category.
class _CategoryProductGrid extends ConsumerStatefulWidget {
  const _CategoryProductGrid({super.key, required this.category, this.keyword = ''});

  final Category category;

  /// Narrows the grid to this category's matching products. Empty means the
  /// whole category.
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

      // Two endpoints, one behaviour. browseByCategoryFiltered is what makes
      // "Search in {category}" actually search inside the category - the box
      // used to open GLOBAL search, so the label was a lie and results came
      // back from shelves the customer had not asked about.
      //
      // The plain endpoint is kept for the unfiltered case rather than always
      // using the filtered one: it is the path every category open takes, and
      // it is already the cached, indexed one.
      final List<Product> fetched;
      final bool serverSaysMore;

      if (widget.keyword.isEmpty) {
        fetched = await repository.browseByCategory(
          widget.category.id,
          page: _nextPage,
          size: _pageSize,
        );
        // browseByCategory returns a bare List, so "a short page means the
        // end" is the only signal available here.
        serverSaysMore = fetched.length >= _pageSize;
      } else {
        final result = await repository.browseByCategoryFiltered(
          categoryId: widget.category.id,
          keyword: widget.keyword,
          page: _nextPage,
          size: _pageSize,
        );
        fetched = result.products;
        // This endpoint reports totalPages, which is a real answer rather
        // than an inference from page length.
        serverSaysMore = _nextPage + 1 < result.totalPages;
      }

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
      return const _Message(
        icon: Icons.inventory_2_outlined,
        text: 'No products available here yet.',
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
