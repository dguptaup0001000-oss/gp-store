import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/brand_avatar.dart';
import '../../../shared/widgets/cart_aware_product_card.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/product_card.dart';
import '../../../shared/widgets/product_filter_controls.dart';
import '../domain/brand_models.dart';
import 'brand_feed_controller.dart';
import 'product_detail_screen.dart';
import 'products_providers.dart';

/// One continuous brand feed: the brand the customer opened, then the next
/// brand, then the next, until the catalogue is exhausted.
///
/// The scroll does not stop when a brand runs out of products - it rolls into
/// the following brand with its own header, so browsing "Lizol" carries on
/// into Harpic, Surf Excel and the rest rather than dead-ending.
///
/// The state machine lives in BrandFeedController; this file is the view.
class BrandProductsScreen extends ConsumerStatefulWidget {
  const BrandProductsScreen({super.key, required this.brand});

  final BrandSummary brand;

  @override
  ConsumerState<BrandProductsScreen> createState() => _BrandProductsScreenState();
}

class _BrandProductsScreenState extends ConsumerState<BrandProductsScreen> {
  late final BrandFeedController _feed;
  final _searchController = TextEditingController();
  Timer? _debounce;

  @override
  void initState() {
    super.initState();
    final repository = ref.read(productsRepositoryProvider);
    _feed = BrandFeedController(
      anchorBrand: widget.brand,
      loadBrands: repository.getBrands,
      fetchPage: ({required brand, sort, inStockOnly = false, keyword, required page}) {
        return repository.browseByBrand(
          brand: brand,
          sort: sort,
          inStockOnly: inStockOnly,
          keyword: keyword,
          page: page,
        );
      },
    )..addListener(_onFeedChanged);
    _feed.start();
  }

  void _onFeedChanged() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    _feed.removeListener(_onFeedChanged);
    _feed.dispose();
    super.dispose();
  }

  void _onSearchChanged(String value) {
    // Debounced so a five-letter word is one request, not five.
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () => _feed.setKeyword(value));
  }

  bool _onScroll(ScrollNotification notification) {
    // Only the page's own vertical scroll. Horizontal carousels and nested
    // scrollables emit notifications too, and reacting to those would fetch
    // brands because somebody flicked something sideways.
    if (notification.depth != 0 || notification.metrics.axis != Axis.vertical) return false;

    // Prefetch a screen and a half early, so the next brand is usually
    // already in place by the time the customer scrolls to where it goes.
    //
    // Measured in viewports rather than as a percentage of content: "75% of
    // the way down" fires instantly on a short brand and far too late on a
    // long one, whereas "less than 1.5 screens left" is the same real
    // distance whatever the content length.
    final remaining = notification.metrics.extentAfter;
    if (remaining < notification.metrics.viewportDimension * 1.5) {
      // Safe to call on every frame: it no-ops while a request is in flight
      // and once the catalogue is finished.
      _feed.advance();
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.brand.brand, overflow: TextOverflow.ellipsis)),
      bottomNavigationBar: const CartSummaryBar(),
      body: NotificationListener<ScrollNotification>(
        onNotification: _onScroll,
        child: CustomScrollView(
          slivers: [
            SliverToBoxAdapter(
              child: _BrandControls(
                brand: widget.brand,
                searchController: _searchController,
                onSearchChanged: _onSearchChanged,
                sort: _feed.sort,
                inStockOnly: _feed.inStockOnly,
                onSortChanged: _feed.setSort,
                onInStockChanged: _feed.setInStockOnly,
              ),
            ),
            ..._buildFeedSlivers(),
          ],
        ),
      ),
    );
  }

  List<Widget> _buildFeedSlivers() {
    if (_feed.isInitialLoading) {
      return const [
        SliverFillRemaining(
          hasScrollBody: false,
          child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      ];
    }

    if (_feed.errorMessage != null && _feed.isEmpty) {
      return [
        SliverFillRemaining(
          hasScrollBody: false,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 32),
                  child: Text(_feed.errorMessage!, textAlign: TextAlign.center),
                ),
                TextButton(onPressed: _feed.retry, child: const Text('Retry')),
              ],
            ),
          ),
        ),
      ];
    }

    if (_feed.isEmpty) {
      return [
        SliverFillRemaining(
          hasScrollBody: false,
          child: Center(
            child: Text(
              _feed.isSearching
                  ? 'Nothing in ${widget.brand.brand} matches that'
                  : 'No products found',
              style: const TextStyle(color: AppColors.textSecondary),
            ),
          ),
        ),
      ];
    }

    final slivers = <Widget>[];

    for (var i = 0; i < _feed.sections.length; i++) {
      final section = _feed.sections[i];
      slivers.add(
        SliverToBoxAdapter(
          child: _BrandSectionHeader(
            brand: section.brand,
            // The anchor brand is already named in the app bar and by the
            // controls above it, so repeating its header immediately below
            // would be the same name three times in one screen.
            showDivider: i > 0,
            isAnchor: i == 0,
          ),
        ),
      );
      slivers.add(
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          // SliverGrid, not a GridView nested in a list: one viewport means
          // only the tiles actually on screen get built, however many brands
          // deep the feed has grown.
          sliver: SliverGrid(
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              mainAxisSpacing: 12,
              crossAxisSpacing: 12,
              childAspectRatio: ProductGrid.aspectRatio(context),
            ),
            delegate: SliverChildBuilderDelegate(
              (context, index) {
                final product = section.products[index];
                return CartAwareProductCard(
                  product: product,
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => ProductDetailScreen(product: product)),
                  ),
                );
              },
              childCount: section.products.length,
            ),
          ),
        ),
      );
    }

    slivers.add(SliverToBoxAdapter(child: _buildFooter()));
    return slivers;
  }

  Widget _buildFooter() {
    if (_feed.errorMessage != null) {
      // Everything already loaded stays on screen; only the part that failed
      // gets a retry.
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        child: Column(
          children: [
            Text(
              "Couldn't load more right now",
              style: const TextStyle(color: AppColors.textSecondary),
            ),
            TextButton(onPressed: _feed.advance, child: const Text('Try again')),
          ],
        ),
      );
    }

    if (_feed.isLoadingMore) {
      return const Padding(
        padding: EdgeInsets.fromLTRB(16, 0, 16, 28),
        child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
      );
    }

    if (_feed.reachedEnd && !_feed.isSearching) {
      return const Padding(
        padding: EdgeInsets.fromLTRB(16, 8, 16, 32),
        child: Column(
          children: [
            Icon(Icons.check_circle_outline, color: AppColors.secondary, size: 26),
            SizedBox(height: 8),
            Text(
              "You've reached the end",
              style: TextStyle(fontWeight: FontWeight.w700, color: AppColors.textPrimary),
            ),
            SizedBox(height: 2),
            Text(
              "You've seen all available brands.",
              style: TextStyle(fontSize: 13, color: AppColors.textSecondary),
            ),
          ],
        ),
      );
    }

    // Reserved space while the next chunk is on its way, so the feed does not
    // end abruptly at the last card.
    return const SizedBox(height: 28);
  }
}

/// The brand storefront band plus the search and filter controls.
///
/// Scrolls away with the content rather than being pinned: on a 5-inch phone
/// a permanently fixed 150px of chrome is a third of the screen, and the
/// customer is here to look at products.
class _BrandControls extends StatelessWidget {
  const _BrandControls({
    required this.brand,
    required this.searchController,
    required this.onSearchChanged,
    required this.sort,
    required this.inStockOnly,
    required this.onSortChanged,
    required this.onInStockChanged,
  });

  final BrandSummary brand;
  final TextEditingController searchController;
  final ValueChanged<String> onSearchChanged;
  final BrandSortOption? sort;
  final bool inStockOnly;
  final ValueChanged<BrandSortOption?> onSortChanged;
  final ValueChanged<bool> onInStockChanged;

  Future<void> _openSortSheet(BuildContext context) async {
    final selected = await SortSheet.show(context, current: sort);
    if (selected == null) return; // dismissed, not a choice of Default
    onSortChanged(selected.first);
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _BrandStorefrontBand(brand: brand),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
          child: Column(
            children: [
              TextField(
                controller: searchController,
                onChanged: onSearchChanged,
                decoration: InputDecoration(
                  hintText: 'Search within ${brand.brand}',
                  prefixIcon: const Icon(Icons.search, size: 20),
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: FilterPill(
                      icon: Icons.swap_vert,
                      prefix: 'Sort by',
                      label: sort?.label ?? 'Default',
                      isActive: sort != null,
                      onTap: () => _openSortSheet(context),
                    ),
                  ),
                  const SizedBox(width: 8),
                  FilterPill(
                    icon: Icons.inventory_2_outlined,
                    label: 'In stock',
                    isActive: inStockOnly,
                    onTap: () => onInStockChanged(!inStockOnly),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// The band that turns a filtered list into a storefront.
///
/// Warm ivory rather than the page's mint ground, because "premium sections
/// use cream/ivory" is the one place in the palette where a surface changes
/// colour to mean something - here, that you have entered a brand's own space
/// rather than a generic results page.
class _BrandStorefrontBand extends StatelessWidget {
  const _BrandStorefrontBand({required this.brand});

  final BrandSummary brand;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 18),
      decoration: const BoxDecoration(
        color: AppColors.ivory,
        border: Border(bottom: BorderSide(color: AppColors.divider)),
      ),
      child: Row(
        children: [
          // Lifted off the ivory so the mark reads as an object on paper -
          // the same product-on-card depth used throughout the app, scaled to
          // a logo.
          Container(
            decoration: const BoxDecoration(shape: BoxShape.circle, boxShadow: AppElevation.tile),
            child: BrandAvatar(brandName: brand.brand, size: 52),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  brand.brand,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 19,
                    fontWeight: FontWeight.w800,
                    color: AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  brandProductCountLabel(brand.productCount),
                  style: const TextStyle(fontSize: 13, color: AppColors.textSecondary),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Singular/plural rather than "1 products", which is the kind of detail that
/// makes an app feel unfinished. Shared by the storefront band and the
/// in-feed section headers so the two can never word it differently.
String brandProductCountLabel(int count) => count == 1 ? '1 product' : '$count products';

/// The header that appears above each brand as the feed rolls into it.
class _BrandSectionHeader extends StatelessWidget {
  const _BrandSectionHeader({
    required this.brand,
    required this.showDivider,
    required this.isAnchor,
  });

  final BrandSummary brand;
  final bool showDivider;
  final bool isAnchor;

  @override
  Widget build(BuildContext context) {
    // The brand you opened is already named in the app bar and the storefront
    // band directly above; a third copy of the same name would just be noise.
    if (isAnchor) return const SizedBox(height: 4);

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (showDivider) ...[
            // A hairline in the ground's own hue, not a heavy rule: this
            // marks a change of brand within one continuous feed, so it
            // should read as a seam rather than as the end of a page.
            const Divider(color: AppColors.divider, height: 1, thickness: 1),
            const SizedBox(height: 18),
          ],
          Row(
            children: [
              BrandAvatar(brandName: brand.brand, size: 34),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      brand.brand,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w800,
                        color: AppColors.textPrimary,
                      ),
                    ),
                    Text(
                      brandProductCountLabel(brand.productCount),
                      style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
