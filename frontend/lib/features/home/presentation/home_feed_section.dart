import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/product_card.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../products/domain/product_models.dart';
import '../../products/presentation/product_feed_provider.dart';
import '../../wishlist/presentation/wishlist_providers.dart';
import '../../../core/util/haptic_widgets.dart';

/// The endless "All Products" feed at the foot of the home screen.
///
/// Returns SLIVERS rather than a widget, and that is the point. The obvious
/// implementation - a GridView inside the existing scrolling column - builds
/// every tile at once because a nested scrollable with shrinkWrap has no
/// viewport to cull against. With a catalogue of thousands that is the whole
/// list in memory and a visibly janky scroll. As slivers, the grid shares
/// the page's single viewport and only builds what is on screen.
class HomeFeedSlivers {
  const HomeFeedSlivers._();

  /// Takes the feed state rather than watching it, and that is deliberate on
  /// two counts.
  ///
  /// The gate: the feed is the largest single payload on the screen and sits
  /// at the very bottom of it, so it waits for the above-the-fold wave to
  /// settle rather than competing with it (see homeBelowFoldReadyProvider).
  /// NOT watching the provider is what withholds the request; rendering a
  /// spinner over a watch that already fired would only hide it.
  ///
  /// And the watch itself belongs to the caller. This function runs inside
  /// ScrollToTop's builder callback, which the framework invokes during
  /// ScrollToTop's build - not during HomeScreen's - so a ref.watch here
  /// would be registering a dependency on an element that has already
  /// finished building. HomeScreen watches, and passes the value down.
  static List<Widget> build(BuildContext context, WidgetRef ref,
      {required AsyncValue<ProductFeedState> feed,
      required void Function(Product product) onProductTap}) {
    final feedAsync = feed;

    return [
      const SliverToBoxAdapter(child: _FeedHeader()),
      feedAsync.when(
        loading: () => const SliverToBoxAdapter(
          child: Padding(
            padding: EdgeInsets.symmetric(vertical: 32),
            child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
          ),
        ),
        error: (error, _) => SliverToBoxAdapter(
          child: _FeedMessage(
            icon: Icons.wifi_off_rounded,
            title: 'Could not load products',
            action: 'Try again',
            onAction: () => ref.invalidate(productFeedProvider),
          ),
        ),
        data: (feed) {
          if (feed.isEmpty) {
            return const SliverToBoxAdapter(
              child: _FeedMessage(
                icon: Icons.inventory_2_outlined,
                title: 'No products available yet',
              ),
            );
          }
          return _FeedGrid(feed: feed, onProductTap: onProductTap);
        },
      ),
      SliverToBoxAdapter(
        child: feedAsync.maybeWhen(
          data: (feed) => _FeedFooter(feed: feed),
          orElse: () => const SizedBox(height: 24),
        ),
      ),
    ];
  }
}

class _FeedHeader extends StatelessWidget {
  const _FeedHeader();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 12),
      child: Row(
        children: [
          // Lavender bar: the palette's "curated / browse more" accent, so
          // this section reads as different from the promotional ones
          // without inventing a new colour.
          Container(
            width: 4,
            height: 20,
            decoration: BoxDecoration(
              color: AppColors.highlight,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            'All Products',
            style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
          ),
        ],
      ),
    );
  }
}

class _FeedGrid extends ConsumerWidget {
  const _FeedGrid({required this.feed, required this.onProductTap});

  final ProductFeedState feed;
  final void Function(Product product) onProductTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Same controllers the existing carousels use, reached the same way, so
    // a product added from the feed behaves identically to one added from
    // "Trending now" - same cart call, same wishlist state, no second source
    // of truth.
    ref.watch(wishlistControllerProvider);
    final wishlistController = ref.read(wishlistControllerProvider.notifier);

    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      sliver: SliverGrid(
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 3,
          mainAxisSpacing: 12,
          crossAxisSpacing: 12,
          childAspectRatio: ProductGrid.aspectRatio(context, columns: 3),
        ),
        delegate: SliverChildBuilderDelegate(
          (context, index) {
            final product = feed.products[index];
            final variant = product.primaryVariant;
            return ProductCard(
              key: ValueKey<int>(product.id),
              product: product,
              onTap: hapticize(() => onProductTap(product)),
              isWishlisted: wishlistController.isWishlisted(product.id),
              onWishlistToggle: () => wishlistController.toggle(product.id),
              onAddPressed: variant == null
                  ? null
                  : () => _addToCart(context, ref, product, variant),
            );
          },
          childCount: feed.products.length,
          // Keyed by product id so Flutter reuses the right element when the
          // list grows. Without this, appending a page can rebuild tiles
          // against the wrong state and flash the previous product's image.
          findChildIndexCallback: (key) {
            final id = (key as ValueKey<int>).value;
            final index = feed.products.indexWhere((p) => p.id == id);
            return index == -1 ? null : index;
          },
        ),
      ),
    );
  }
}

Future<void> _addToCart(
    BuildContext context, WidgetRef ref, Product product, ProductVariant variant) async {
  try {
    await ref.read(cartControllerProvider.notifier).addToCart(variantId: variant.id, quantity: 1);
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('${product.name} added to cart')),
    );
  } catch (e) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
  }
}

/// End-of-feed area: the next-page spinner, a retry when a page failed, or
/// the "you have seen everything" note.
class _FeedFooter extends ConsumerWidget {
  const _FeedFooter({required this.feed});

  final ProductFeedState feed;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (feed.error != null) {
      return _FeedMessage(
        icon: Icons.error_outline,
        title: 'Could not load more products',
        action: 'Retry',
        onAction: () => ref.read(productFeedProvider.notifier).retryLoadMore(),
      );
    }

    if (feed.isLoadingMore) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 24),
        child: Center(
          child: SizedBox(height: 22, width: 22, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      );
    }

    if (!feed.hasNext && feed.products.isNotEmpty) {
      // Says the list ENDED rather than leaving the customer at a dead stop
      // wondering whether something failed to load.
      return const Padding(
        padding: EdgeInsets.fromLTRB(16, 24, 16, 32),
        child: Center(
          child: Text(
            "You're all caught up",
            style: TextStyle(color: AppColors.textSecondary, fontWeight: FontWeight.w500),
          ),
        ),
      );
    }

    return const SizedBox(height: 32);
  }
}

class _FeedMessage extends StatelessWidget {
  const _FeedMessage({required this.icon, required this.title, this.action, this.onAction});

  final IconData icon;
  final String title;
  final String? action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 32, horizontal: 16),
      child: Column(
        children: [
          Icon(icon, size: 36, color: AppColors.textSecondary),
          const SizedBox(height: 12),
          Text(title, style: const TextStyle(color: AppColors.textSecondary)),
          if (action != null) ...[
            const SizedBox(height: 12),
            OutlinedButton(onPressed: onAction, child: Text(action!)),
          ],
        ],
      ),
    );
  }
}
