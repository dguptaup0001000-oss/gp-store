import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/theme/customer_surface_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../address/presentation/address_list_screen.dart';
import '../domain/marketplace_feed_models.dart';
import 'marketplace_card_actions.dart';
import 'marketplace_card_tile.dart';
import 'marketplace_feed_provider.dart';

/// Full, paginated catalogue for Visit to Buy or Service at Shop.
///
/// Home keeps these modes as short horizontal previews. This screen shares
/// the same mode-specific feed provider, so it continues from the preview's
/// first page and keeps the customer's current location and shop filter.
class MarketplaceModeProductsScreen extends ConsumerWidget {
  const MarketplaceModeProductsScreen({
    super.key,
    required this.mode,
  });

  final CommerceMode mode;

  String get _title => switch (mode) {
        CommerceMode.buyOnline => 'Buy Online',
        CommerceMode.visitToBuy => 'Visit to Buy',
        CommerceMode.serviceAtShop => 'Service at Shop',
      };

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
            Text(_title,
                style:
                    const TextStyle(fontSize: 17, fontWeight: FontWeight.w800)),
            Text(
              selectedShop == null ? 'All nearby shops' : 'Selected shop',
              style: const TextStyle(fontSize: 11.5, color: Colors.white70),
            ),
          ],
        ),
      ),
      bottomNavigationBar: const CartSummaryBar(),
      body: pin == null
          ? Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.location_on_outlined, size: 32),
                  const SizedBox(height: 8),
                  const Text('Add an address to find nearby listings.'),
                  const SizedBox(height: 8),
                  OutlinedButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => const AddressListScreen(),
                      ),
                    ),
                    child: const Text('Choose address'),
                  ),
                ],
              ),
            )
          : _ModeProductsGrid(mode: mode),
    );
  }
}

class _ModeProductsGrid extends ConsumerWidget {
  const _ModeProductsGrid({required this.mode});

  final CommerceMode mode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = marketplaceHomeModeFeedProvider(mode);
    final feed = ref.watch(provider);
    final controller = ref.read(provider.notifier);

    if (feed.isLoading && feed.cards.isEmpty) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }
    if (feed.error != null && feed.cards.isEmpty) {
      return Center(
        child: TextButton.icon(
          onPressed: hapticize(controller.retry),
          icon: const Icon(Icons.refresh_rounded),
          label: const Text("Couldn't load nearby listings. Retry"),
        ),
      );
    }
    if (feed.cards.isEmpty) {
      return Center(
        child: Text('No nearby ${_emptyLabel(mode)} found.'),
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        const horizontalPadding = 12.0;
        const spacing = 10.0;
        final columns = constraints.maxWidth >= 900
            ? 4
            : constraints.maxWidth >= 600
                ? 3
                : 2;
        final cardWidth = (constraints.maxWidth -
                horizontalPadding * 2 -
                spacing * (columns - 1)) /
            columns;
        final cardHeight = MarketplaceCardTile.carouselHeight(
          context,
          cardWidth: cardWidth,
        );

        return NotificationListener<ScrollNotification>(
          onNotification: (notification) {
            if (notification.depth == 0 &&
                notification.metrics.axis == Axis.vertical &&
                notification.metrics.extentAfter < 600) {
              controller.loadMore();
            }
            return false;
          },
          child: CustomScrollView(
            cacheExtent: 700,
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(
                  horizontalPadding,
                  12,
                  horizontalPadding,
                  18,
                ),
                sliver: SliverGrid(
                  gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: columns,
                    mainAxisSpacing: spacing,
                    crossAxisSpacing: spacing,
                    childAspectRatio: cardWidth / cardHeight,
                  ),
                  delegate: SliverChildBuilderDelegate(
                    (context, index) {
                      final card = feed.cards[index];
                      return MarketplaceCardTile(
                        key: ValueKey<String>('mode:${card.feedKey}'),
                        card: card,
                        compact: false,
                        onTap: () =>
                            MarketplaceCardActions.open(context, ref, card),
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
        );
      },
    );
  }

  static String _emptyLabel(CommerceMode mode) => switch (mode) {
        CommerceMode.visitToBuy => 'in-store products',
        CommerceMode.serviceAtShop => 'services',
        CommerceMode.buyOnline => 'products',
      };
}
