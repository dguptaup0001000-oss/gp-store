import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/theme/customer_surface_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/scroll_to_top.dart';
import '../../address/presentation/address_list_screen.dart';
import '../domain/marketplace_feed_models.dart';
import 'marketplace_card_actions.dart';
import 'marketplace_card_tile.dart';
import 'marketplace_feed_provider.dart';

/// A human product family rather than a single SKU.
///
/// This is intentionally a search term the marketplace backend already knows
/// how to apply across product name, category, subcategory and search keywords.
/// It keeps the first tap broad ("Salt") and the next tap exact (one packet).
class MarketplaceProductFamily {
  const MarketplaceProductFamily({required this.title, required this.query});

  final String title;
  final String query;

  factory MarketplaceProductFamily.fromCard(MarketplaceCard card) {
    final text =
        '${card.name} ${card.categoryName ?? ''}'.toLowerCase();

    const families = <({List<String> terms, String title, String query})>[
      (terms: ['namak', 'salt'], title: 'Salt', query: 'salt'),
      (terms: ['namkeen'], title: 'Namkeen', query: 'namkeen'),
      (terms: ['soap'], title: 'Soap', query: 'soap'),
      (terms: ['shampoo'], title: 'Shampoo', query: 'shampoo'),
      (terms: ['laptop'], title: 'Laptops', query: 'laptop'),
      (terms: ['smartphone', 'iphone', 'mobile', 'phone'], title: 'Phones', query: 'phone'),
      (terms: ['atta', 'flour'], title: 'Atta & Flour', query: 'atta'),
      (terms: ['rice', 'chawal'], title: 'Rice', query: 'rice'),
      (terms: ['dal', 'lentil'], title: 'Dal', query: 'dal'),
      (terms: ['oil'], title: 'Oils', query: 'oil'),
      (terms: ['biscuit', 'cookie'], title: 'Biscuits', query: 'biscuit'),
      (terms: ['milk'], title: 'Milk', query: 'milk'),
      (terms: ['paneer'], title: 'Paneer', query: 'paneer'),
      (terms: ['tea'], title: 'Tea', query: 'tea'),
      (terms: ['coffee'], title: 'Coffee', query: 'coffee'),
      (terms: ['saree'], title: 'Sarees', query: 'saree'),
      (terms: ['shoe', 'footwear'], title: 'Footwear', query: 'shoe'),
      (terms: ['charger'], title: 'Chargers', query: 'charger'),
      (terms: ['earbud'], title: 'Earbuds', query: 'earbud'),
    ];

    for (final family in families) {
      if (family.terms.any(text.contains)) {
        return MarketplaceProductFamily(
          title: family.title,
          query: family.query,
        );
      }
    }

    // The catalogue category is the safest generic fallback: it is authored
    // data, not a word guessed from a brand name. Categories that are broad
    // still give the customer a useful infinite cross-shop browse.
    final category = card.categoryName?.trim();
    if (category != null && category.isNotEmpty) {
      return MarketplaceProductFamily(title: category, query: category);
    }

    // Last resort for old/manual rows without category metadata: remove the
    // deterministic test suffix and pack-size noise before searching.
    var fallback = card.name
        .replaceAll(RegExp(r'\s+TEST\s+\d{3}-\d{3}.*$', caseSensitive: false), '')
        .replaceAll(RegExp(r'\b\d+(?:\.\d+)?\s*(?:kg|g|l|ml|pcs?)\b',
            caseSensitive: false), '')
        .trim();
    if (fallback.isEmpty) fallback = card.name.trim();
    return MarketplaceProductFamily(title: fallback, query: fallback);
  }
}

class MarketplaceProductFamilyScreen extends ConsumerWidget {
  const MarketplaceProductFamilyScreen({
    super.key,
    required this.family,
  });

  final MarketplaceProductFamily family;

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
            Text(
              family.title,
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
            ),
            Text(
              selectedShop == null ? 'All nearby shops' : 'Selected shop',
              style: const TextStyle(fontSize: 11.5, color: Colors.white70),
            ),
          ],
        ),
      ),
      bottomNavigationBar: const CartSummaryBar(),
      body: pin == null
          ? _NeedAddress(
              onOpen: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const AddressListScreen()),
              ),
            )
          : _FamilyGrid(family: family),
    );
  }
}

class _FamilyGrid extends ConsumerWidget {
  const _FamilyGrid({required this.family});

  final MarketplaceProductFamily family;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final feed = ref.watch(marketplaceFamilyFeedProvider(family.query));
    final controller =
        ref.read(marketplaceFamilyFeedProvider(family.query).notifier);

    if (feed.isLoading && feed.cards.isEmpty) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }
    if (feed.error != null && feed.cards.isEmpty) {
      return Center(
        child: TextButton.icon(
          onPressed: hapticize(controller.retry),
          icon: const Icon(Icons.refresh_rounded),
          label: const Text("Couldn't load nearby products. Retry"),
        ),
      );
    }
    if (feed.cards.isEmpty) {
      return Center(
        child: Text('No nearby ${family.title.toLowerCase()} found yet.'),
      );
    }

    return RefreshIndicator(
      onRefresh: () async =>
          ref.invalidate(marketplaceFamilyFeedProvider(family.query)),
      child: ScrollToTop(
        builder: (context, scrollController) =>
            NotificationListener<ScrollNotification>(
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
                        key: ValueKey<String>('family:${card.feedKey}'),
                        card: card,
                        compact: true,
                        // The first tap opened the family. A tap inside the
                        // family is now exact and opens this product/seller.
                        onTap: () => MarketplaceCardActions.openDetail(
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
                                    'All nearby matches loaded',
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
              const Icon(
                Icons.location_off_outlined,
                size: 42,
                color: AppColors.textSecondary,
              ),
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
