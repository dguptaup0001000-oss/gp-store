import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_theme.dart';
import '../../features/auth/presentation/auth_providers.dart';
import '../../features/cart/presentation/cart_providers.dart';
import '../../features/products/domain/brand_models.dart';
import '../../features/products/domain/product_models.dart';
import '../../features/products/presentation/product_detail_screen.dart';
import '../../features/wishlist/presentation/wishlist_providers.dart';
import 'product_card.dart';

/// Note: the sort enum is named BrandSortOption for historical reasons (it
/// was built for Shop by Brand first) - its eight values (price, name,
/// newest, discount, best selling, highest rated) aren't actually
/// brand-specific in meaning, which is exactly why this widget can reuse it
/// for category browsing too without needing a second, identical enum.
typedef ProductPageFetcher = Future<({List<Product> products, int totalElements, int totalPages})> Function({
  BrandSortOption? sort,
  bool inStockOnly,
  String? keyword,
  required int page,
});

/// One reusable "browse a filtered/sorted/searched set of products" screen
/// body - used by both Shop by Brand and category browsing, so the two
/// features can never silently drift apart in behavior, and a future fix
/// only needs to happen once. The caller supplies the Scaffold/AppBar and
/// just passes this as the body, plus the fetch function for their
/// specific scope (one brand, one category).
class FilteredProductBrowser extends ConsumerStatefulWidget {
  const FilteredProductBrowser({
    super.key,
    required this.searchHint,
    required this.fetchPage,
    this.header,
  });

  final String searchHint;
  final ProductPageFetcher fetchPage;

  /// Optional band shown above the search field - the brand storefront
  /// header uses it. A slot rather than a second copy of this widget, so
  /// brand and category browsing keep sharing one implementation and cannot
  /// drift apart.
  final Widget? header;

  @override
  ConsumerState<FilteredProductBrowser> createState() => _FilteredProductBrowserState();
}

class _FilteredProductBrowserState extends ConsumerState<FilteredProductBrowser> {
  final _searchController = TextEditingController();
  Timer? _debounce;

  BrandSortOption? _sort;
  bool _inStockOnly = false;
  String _keyword = '';

  List<Product> _products = [];
  int _currentPage = 0;
  int _totalPages = 1;
  bool _isLoading = true;
  bool _isLoadingMore = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _loadPage(0);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadPage(int page, {bool append = false}) async {
    setState(() {
      if (append) {
        _isLoadingMore = true;
      } else {
        _isLoading = true;
        _errorMessage = null;
      }
    });

    try {
      final result = await widget.fetchPage(
        sort: _sort,
        inStockOnly: _inStockOnly,
        keyword: _keyword,
        page: page,
      );

      if (!mounted) return;
      setState(() {
        _products = append ? [..._products, ...result.products] : result.products;
        _currentPage = page;
        _totalPages = result.totalPages;
        _isLoading = false;
        _isLoadingMore = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage = extractErrorMessage(e);
        _isLoading = false;
        _isLoadingMore = false;
      });
    }
  }

  void _onSearchChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () {
      setState(() => _keyword = value.trim());
      _loadPage(0);
    });
  }

  Future<void> _addToCart(Product product) async {
    final variant = product.primaryVariant;
    if (variant == null) return;
    try {
      await ref.read(cartControllerProvider.notifier).addToCart(variantId: variant.id, quantity: 1);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${product.name} added to cart')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }

  /// Sort options as a bottom sheet rather than a dropdown menu.
  ///
  /// Eight options in a Material dropdown open as a cramped overlay pinned to
  /// the field; on a phone a sheet gives each row a real tap target and shows
  /// the current choice, which is what a shopper is actually checking when
  /// they open it.
  Future<void> _openSortSheet() async {
    final selected = await showModalBottomSheet<Object?>(
      context: context,
      backgroundColor: AppColors.cardBackground,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadius.lg)),
      ),
      builder: (sheetContext) {
        Widget row(String label, BrandSortOption? value) {
          final isSelected = _sort == value;
          return ListTile(
            title: Text(
              label,
              style: TextStyle(
                fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
                color: isSelected ? AppColors.primary : AppColors.textPrimary,
              ),
            ),
            trailing: isSelected ? const Icon(Icons.check, color: AppColors.primary, size: 20) : null,
            // Wrapped in a one-element list because null is also a valid
            // selection ("Default"), and popping null would be
            // indistinguishable from the sheet being dismissed.
            onTap: () => Navigator.of(sheetContext).pop(<BrandSortOption?>[value]),
          );
        }

        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 16, 16, 4),
                child: Text('Sort by', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
              ),
              row('Default', null),
              ...BrandSortOption.values.map((option) => row(option.label, option)),
              const SizedBox(height: 8),
            ],
          ),
        );
      },
    );

    if (selected is! List<BrandSortOption?>) return; // dismissed
    final value = selected.first;
    if (value == _sort) return; // no refetch for a no-op choice
    setState(() => _sort = value);
    _loadPage(0);
  }

  @override
  Widget build(BuildContext context) {
    // Watched (not just read) so hearts update live if toggled from here.
    ref.watch(wishlistControllerProvider);
    final wishlistController = ref.read(wishlistControllerProvider.notifier);

    return Column(
      children: [
        if (widget.header != null) widget.header!,
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
          child: Column(
            children: [
              TextField(
                controller: _searchController,
                onChanged: _onSearchChanged,
                decoration: InputDecoration(
                  hintText: widget.searchHint,
                  prefixIcon: const Icon(Icons.search, size: 20),
                ),
              ),
              const SizedBox(height: 12),
              // Two pills rather than a form dropdown beside a bare Switch.
              // The old row was two different control languages side by side
              // and cost ~72px of vertical space above every grid; these read
              // as one set of filters and give that space back to products.
              Row(
                children: [
                  Expanded(
                    child: _FilterPill(
                      icon: Icons.swap_vert,
                      label: _sort?.label ?? 'Default',
                      prefix: 'Sort by',
                      isActive: _sort != null,
                      onTap: _openSortSheet,
                    ),
                  ),
                  const SizedBox(width: 8),
                  _FilterPill(
                    icon: Icons.inventory_2_outlined,
                    label: 'In stock',
                    isActive: _inStockOnly,
                    onTap: () {
                      setState(() => _inStockOnly = !_inStockOnly);
                      _loadPage(0);
                    },
                  ),
                ],
              ),
            ],
          ),
        ),
        Expanded(child: _buildBody(wishlistController)),
      ],
    );
  }

  Widget _buildBody(WishlistController wishlistController) {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }

    if (_errorMessage != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_errorMessage!),
            TextButton(onPressed: () => _loadPage(0), child: const Text('Retry')),
          ],
        ),
      );
    }

    if (_products.isEmpty) {
      return const Center(
        child: Text('No products found', style: TextStyle(color: AppColors.textSecondary)),
      );
    }

    final hasMore = _currentPage + 1 < _totalPages;

    return GridView.builder(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: ProductGrid.aspectRatio(context),
      ),
      itemCount: _products.length + (hasMore ? 1 : 0),
      itemBuilder: (context, index) {
        if (index == _products.length) {
          if (!_isLoadingMore) {
            WidgetsBinding.instance.addPostFrameCallback((_) => _loadPage(_currentPage + 1, append: true));
          }
          return const Center(child: CircularProgressIndicator(strokeWidth: 2));
        }

        final product = _products[index];
        return ProductCard(
          product: product,
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => ProductDetailScreen(product: product)),
          ),
          onAddPressed: () => _addToCart(product),
          isWishlisted: wishlistController.isWishlisted(product.id),
          onWishlistToggle: () => wishlistController.toggle(product.id),
        );
      },
    );
  }
}

/// A filter control as a single tappable pill.
///
/// Active state is carried by a green fill and weight rather than by a
/// separate checkbox or switch, so the whole row reads as one language: a
/// pill that is green is a filter that is on.
class _FilterPill extends StatelessWidget {
  const _FilterPill({
    required this.icon,
    required this.label,
    required this.isActive,
    required this.onTap,
    this.prefix,
  });

  final IconData icon;
  final String label;

  /// Small leading word ("Sort by") shown above the value, for the pill whose
  /// label is a chosen value rather than a fixed name.
  final String? prefix;
  final bool isActive;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final foreground = isActive ? AppColors.primary : AppColors.textSecondary;

    return Material(
      color: isActive ? AppColors.tint(AppColors.primary) : AppColors.cardBackground,
      borderRadius: BorderRadius.circular(AppRadius.md),
      child: InkWell(
        borderRadius: BorderRadius.circular(AppRadius.md),
        onTap: () {
          HapticFeedback.selectionClick();
          onTap();
        },
        child: Container(
          height: 46,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(AppRadius.md),
            border: Border.all(
              color: isActive ? AppColors.primary : AppColors.divider,
              width: isActive ? 1.4 : 1,
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 18, color: foreground),
              const SizedBox(width: 8),
              // Flexible, not Expanded: the "In stock" pill sizes to its own
              // content, and Expanded inside a min-width Row would force it
              // to claim the rest of the line.
              Flexible(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (prefix != null)
                      Text(
                        prefix!,
                        style: const TextStyle(fontSize: 10, color: AppColors.textSecondary, height: 1.1),
                      ),
                    Text(
                      label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13,
                        height: 1.2,
                        fontWeight: isActive ? FontWeight.w700 : FontWeight.w600,
                        color: isActive ? AppColors.primary : AppColors.textPrimary,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
