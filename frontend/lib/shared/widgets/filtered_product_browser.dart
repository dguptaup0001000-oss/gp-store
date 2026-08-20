import 'dart:async';

import 'package:flutter/material.dart';
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
  });

  final String searchHint;
  final ProductPageFetcher fetchPage;

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

  @override
  Widget build(BuildContext context) {
    // Watched (not just read) so hearts update live if toggled from here.
    ref.watch(wishlistControllerProvider);
    final wishlistController = ref.read(wishlistControllerProvider.notifier);

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
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
              Row(
                children: [
                  Expanded(
                    child: DropdownButtonFormField<BrandSortOption?>(
                      initialValue: _sort,
                      isExpanded: true,
                      decoration: const InputDecoration(labelText: 'Sort by'),
                      items: [
                        const DropdownMenuItem(value: null, child: Text('Default')),
                        ...BrandSortOption.values.map(
                          (option) => DropdownMenuItem(value: option, child: Text(option.label)),
                        ),
                      ],
                      onChanged: (value) {
                        setState(() => _sort = value);
                        _loadPage(0);
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  Column(
                    children: [
                      Switch(
                        value: _inStockOnly,
                        onChanged: (value) {
                          setState(() => _inStockOnly = value);
                          _loadPage(0);
                        },
                      ),
                      const Text('In stock', style: TextStyle(fontSize: 11)),
                    ],
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
