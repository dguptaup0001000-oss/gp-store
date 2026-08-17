import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/presentation/auth_providers.dart';
import '../../features/cart/presentation/cart_providers.dart';
import '../../features/products/domain/product_models.dart';
import '../../features/wishlist/presentation/wishlist_providers.dart';
import 'product_card.dart';

/// One reusable section for every horizontal product row on the home screen
/// (New Arrivals, Trending, Recommended, etc.) - handles loading, error (with
/// an actual retry, not a dead end), and "nothing here yet" states properly,
/// per the spec's "handle API failures gracefully" rule. Returns an empty
/// widget (not an error banner) when the section is simply empty - an empty
/// "Recommended for you" for a brand-new customer isn't a failure.
class HorizontalProductSection extends ConsumerWidget {
  const HorizontalProductSection({
    super.key,
    required this.title,
    required this.provider,
    required this.onRetry,
    this.onProductTap,
    this.onSeeAllTap,
  });

  final String title;
  final AsyncValue<List<Product>> provider;
  final VoidCallback onRetry;
  final void Function(Product product)? onProductTap;
  final VoidCallback? onSeeAllTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Must watch the actual state (not just .notifier) for this widget to
    // rebuild when a heart is toggled elsewhere - watching .notifier alone
    // doesn't subscribe to state changes, only watching the provider itself does.
    ref.watch(wishlistControllerProvider);
    final wishlistController = ref.read(wishlistControllerProvider.notifier);

    return provider.when(
      loading: () => _SectionShell(
        title: title,
        child: const SizedBox(
          height: 220,
          child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      ),
      error: (error, stackTrace) => _SectionShell(
        title: title,
        child: SizedBox(
          height: 100,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // TEMPORARY, for active debugging - see RootScreen's
                // identical comment for why this shows the real failure
                // reason instead of one static string. This one widget
                // backs New Arrivals, Trending, AND Recommended for you -
                // one fix covers all three sections' error states.
                Text("Couldn't load this: ${extractErrorMessage(error)}", textAlign: TextAlign.center),
                TextButton(onPressed: onRetry, child: const Text('Retry')),
              ],
            ),
          ),
        ),
      ),
      data: (products) {
        if (products.isEmpty) return const SizedBox.shrink();

        return _SectionShell(
          title: title,
          onSeeAllTap: onSeeAllTap,
          child: SizedBox(
            height: 220,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              itemCount: products.length,
              separatorBuilder: (_, __) => const SizedBox(width: 12),
              itemBuilder: (context, index) {
                final product = products[index];
                return SizedBox(
                  width: 148,
                  child: ProductCard(
                    product: product,
                    onTap: onProductTap == null ? null : () => onProductTap!(product),
                    onAddPressed: () => _addToCart(context, ref, product),
                    isWishlisted: wishlistController.isWishlisted(product.id),
                    onWishlistToggle: () => wishlistController.toggle(product.id),
                  ),
                );
              },
            ),
          ),
        );
      },
    );
  }

  Future<void> _addToCart(BuildContext context, WidgetRef ref, Product product) async {
    final variant = product.primaryVariant;
    if (variant == null) return;

    try {
      await ref.read(cartControllerProvider.notifier).addToCart(variantId: variant.id, quantity: 1);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${product.name} added to cart')));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }
}

class _SectionShell extends StatelessWidget {
  const _SectionShell({required this.title, required this.child, this.onSeeAllTap});

  final String title;
  final Widget child;
  final VoidCallback? onSeeAllTap;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleLarge),
              if (onSeeAllTap != null)
                TextButton(onPressed: onSeeAllTap, child: const Text('See All')),
            ],
          ),
        ),
        child,
      ],
    );
  }
}
