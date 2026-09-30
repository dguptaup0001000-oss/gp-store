import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/theme/customer_surface_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/scroll_to_top.dart';
import '../../address/presentation/address_list_screen.dart';
import '../../products/domain/product_models.dart';
import 'marketplace_card_actions.dart';
import 'marketplace_card_tile.dart';
import 'marketplace_feed_provider.dart';

/// Every nearby listing in one real catalogue category.
///
/// Soap can contain 50 or 500 products: this screen requests only 18 at a
/// time, appends the next page near the bottom and keeps the selected shop
/// filter. It never switches to the legacy single-shop feed.
class MarketplaceCategoryProductsScreen extends ConsumerWidget {
  const MarketplaceCategoryProductsScreen({
    super.key,
    required this.category,
  });

  final Category category;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pin = ref.watch(deliveryPinProvider);
    final selectedShop = ref.watch(marketplaceShopFilterProvider);
    final ground = ref.watch(customerSurfaceThemeProvider).ground;

    return Scaffold(
      backgroundColor: ground,
      appBar: AppBar(
        titleSpacing: 0,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(category.name,
                style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800)),
            Text(
              selectedShop == null ? 'All nearby shops' : 'Selected shop',
              style: const TextStyle(fontSize: 11.5, color: Colors.white70),
            ),
          ],
        ),
      ),
      bottomNavigationBar: const CartSummaryBar(),
      body: pin == null
          ? _NeedAddress(onOpen: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const AddressListScreen()),
              ))
          : _CategoryGrid(categoryId: category.id),
    );
  }
}

class _CategoryGrid extends ConsumerWidget {
  const _CategoryGrid({required this.categoryId});

  final int categoryId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final feed = ref.watch(marketplaceCategoryFeedProvider(categoryId));
    final controller =
        ref.read(marketplaceCategoryFeedProvider(categoryId).notifier);

    if (feed.isLoading && feed.cards.isEmpty) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }
    if (feed.error != null && feed.cards.isEmpty) {
      return Center(
        child: TextButton.icon(
          onPressed: hapticize(controller.retry),
          icon: const Icon(Icons.refresh_rounded),
          label: const Text("Couldn't load products. Retry"),
        ),
      );
    }
    if (feed.cards.isEmpty) {
      return const Center(child: Text('No nearby products in this category yet.'));
    }

    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(
        marketplaceCategoryFeedProvider(categoryId),
      ),
      child: ScrollToTop(
        builder: (context, scrollController) => NotificationListener<ScrollNotification>(
          onNotification: (notification) {
            if (notification.depth == 0 &&
                notification.metrics.axis == Axis.vertical &&
                notification.metrics.extentAfter < 800) {
              controller.loadMore();
            }
            return false;
          },
          child: CustomScrollView(
            controller: scrollController,
            cacheExtent: 700,
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(10, 12, 10, 18),
                sliver: SliverGrid(
                  gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 3,
                    mainAxisSpacing: 10,
                    crossAxisSpacing: 8,
                    childAspectRatio:
                        MarketplaceCardTile.gridAspectRatio(context),
                  ),
                  delegate: SliverChildBuilderDelegate(
                    (context, index) {
                      final card = feed.cards[index];
                      return MarketplaceCardTile(
                        key: ValueKey<String>('category:${card.feedKey}'),
                        card: card,
                        compact: true,
                        onTap: () => MarketplaceCardActions.open(
                          context,
                          ref,
                          card,
                        ),
                        onAdd: card.addable
                            ? () => MarketplaceCardActions.addCard(
                                  context,
                                  ref,
                                  card,
                                )
                            : null,
                      );
                    },
                    childCount: feed.cards.length,
                  ),
                ),
              ),
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 24),
                  child: Center(
                    child: feed.isLoadingMore
                        ? const SizedBox.square(
                            dimension: 22,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : feed.error != null
                            ? TextButton(
                                onPressed: hapticize(controller.retry),
                                child: const Text('Retry loading more'),
                              )
                            : feed.hasNext
                                ? const SizedBox(height: 8)
                                : const Text(
                                    'You have reached the end',
                                    style: TextStyle(
                                      color: AppColors.textSecondary,
                                      fontSize: 12,
                                    ),
                                  ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _NeedAddress extends StatelessWidget {
  const _NeedAddress({required this.onOpen});

  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.location_off_outlined,
                  size: 42, color: AppColors.textSecondary),
              const SizedBox(height: 10),
              const Text('Choose an address to see nearby products.'),
              const SizedBox(height: 14),
              FilledButton(
                onPressed: hapticize(onOpen),
                child: const Text('Choose address'),
              ),
            ],
          ),
        ),
      );
}
