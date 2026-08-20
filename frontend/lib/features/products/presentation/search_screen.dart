import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/product_card.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../wishlist/presentation/wishlist_providers.dart';
import '../domain/product_models.dart';
import 'product_detail_screen.dart';
import 'products_providers.dart';

class SearchScreen extends ConsumerStatefulWidget {
  const SearchScreen({super.key});

  @override
  ConsumerState<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends ConsumerState<SearchScreen> {
  final _controller = TextEditingController();
  Timer? _debounce;

  List<Product> _results = [];
  bool _isLoading = false;
  String? _errorMessage;
  bool _hasSearched = false;

  // Debouncing alone only limits how often a request FIRES, not the order
  // responses come back in - a slow/flaky connection can still let an older
  // search's response arrive after a newer one's and stomp its results.
  // Each _search() call claims the next number and only applies its result
  // if it's still the latest call by the time the response arrives.
  int _searchSeq = 0;

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _onQueryChanged(String query) {
    _debounce?.cancel();

    if (query.trim().isEmpty) {
      setState(() {
        _results = [];
        _hasSearched = false;
        _errorMessage = null;
      });
      return;
    }

    // Debounced - avoids firing a real backend search request on every single
    // keystroke, which would be wasteful and could visibly lag on a slow
    // connection (village/tier-3 network conditions matter here).
    _debounce = Timer(const Duration(milliseconds: 400), () => _search(query.trim()));
  }

  Future<void> _search(String query) async {
    final seq = ++_searchSeq;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
      _hasSearched = true;
    });

    try {
      final results = await ref.read(productsRepositoryProvider).searchInstant(query);
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _results = results;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _errorMessage = extractErrorMessage(e);
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    // Watching (not just reading .notifier) so this screen rebuilds when a
    // heart is toggled - same reasoning as HorizontalProductSection.
    ref.watch(wishlistControllerProvider);

    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _controller,
          autofocus: true,
          onChanged: _onQueryChanged,
          decoration: const InputDecoration(
            hintText: 'Search for atta, milk, snacks...',
            border: InputBorder.none,
          ),
        ),
      ),
      body: _buildBody(),
      bottomNavigationBar: const CartSummaryBar(),
    );
  }

  Widget _buildBody() {
    if (!_hasSearched) {
      return const Center(
        child: Text('Search for products', style: TextStyle(color: AppColors.textSecondary)),
      );
    }

    if (_isLoading) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }

    if (_errorMessage != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_errorMessage!),
            TextButton(
              onPressed: () => _search(_controller.text.trim()),
              child: const Text('Retry'),
            ),
          ],
        ),
      );
    }

    if (_results.isEmpty) {
      return const Center(
        child: Text('No products found', style: TextStyle(color: AppColors.textSecondary)),
      );
    }

    return GridView.builder(
      padding: const EdgeInsets.all(16),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: ProductGrid.aspectRatio(context),
      ),
      itemCount: _results.length,
      itemBuilder: (context, index) {
        final product = _results[index];
        final wishlistController = ref.read(wishlistControllerProvider.notifier);
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
}
