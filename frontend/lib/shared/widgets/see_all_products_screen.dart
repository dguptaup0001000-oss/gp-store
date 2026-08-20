import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_theme.dart';
import '../../features/auth/presentation/auth_providers.dart';
import '../../features/cart/presentation/cart_providers.dart';
import '../../features/products/domain/product_models.dart';
import '../../features/products/presentation/product_detail_screen.dart';
import '../../features/wishlist/presentation/wishlist_providers.dart';
import 'product_card.dart';
import 'scroll_to_top.dart';

/// Deliberately NOT a paginated infinite-scroll browser like the brand feed
/// (see BrandFeedController) - trending/new-arrivals/recommended are ranked
/// "top N" lists from aggregation queries, not an exhaustive catalog to
/// page through. This just shows more of the same ranked list (up to the
/// backend's own cap), which is what "See All" actually means here.
class SeeAllProductsScreen extends ConsumerStatefulWidget {
  const SeeAllProductsScreen({super.key, required this.title, required this.fetchProducts});

  final String title;
  final Future<List<Product>> Function() fetchProducts;

  @override
  ConsumerState<SeeAllProductsScreen> createState() => _SeeAllProductsScreenState();
}

class _SeeAllProductsScreenState extends ConsumerState<SeeAllProductsScreen> {
  late Future<List<Product>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.fetchProducts();
  }

  void _retry() {
    setState(() => _future = widget.fetchProducts());
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
    ref.watch(wishlistControllerProvider);
    final wishlistController = ref.read(wishlistControllerProvider.notifier);

    return Scaffold(
      appBar: AppBar(title: Text(widget.title)),
      body: FutureBuilder<List<Product>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator(strokeWidth: 2));
          }

          if (snapshot.hasError) {
            return Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // TEMPORARY, for active debugging - see RootScreen's identical
                  // comment for why this shows the real failure reason instead
                  // of one static string.
                  Text("Couldn't load this: ${extractErrorMessage(snapshot.error!)}"),
                  TextButton(onPressed: _retry, child: const Text('Retry')),
                ],
              ),
            );
          }

          final products = snapshot.data ?? [];
          if (products.isEmpty) {
            return const Center(
              child: Text('Nothing here yet', style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          return ScrollToTop(
            builder: (context, scrollController) => GridView.builder(
            controller: scrollController,
            padding: const EdgeInsets.all(16),
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              mainAxisSpacing: 12,
              crossAxisSpacing: 12,
              childAspectRatio: ProductGrid.aspectRatio(context),
            ),
            itemCount: products.length,
            itemBuilder: (context, index) {
              final product = products[index];
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
            ),
          );
        },
      ),
    );
  }
}
