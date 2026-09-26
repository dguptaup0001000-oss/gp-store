import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../domain/marketplace_feed_models.dart';
import 'marketplace_card_tile.dart';
import 'marketplace_feed_provider.dart';

/// The nearby marketplace feed on the home screen: what is available
/// NEAR THIS CUSTOMER, across every shop that would serve them.
///
/// Replaces the shop-scoped feed on a marketplace deployment. That one asks
/// "what does the shop I am in sell", which for a customer who has chosen no
/// shop resolved to Shop #1 - so a screen labelled All Products showed one
/// kirana's shelf, or the words "No products available yet" while shops full
/// of stock stood a street away.
///
/// These slivers live directly in Home's one vertical CustomScrollView.
/// Each horizontal mode rail owns a separate bounded, paginated request.
class MarketplaceFeedSlivers {
  const MarketplaceFeedSlivers._();

  /// Home rails are separate bounded requests, rather than groups cut out of
  /// the first page of the mixed feed. A page dominated by Buy Online must
  /// not make real Visit-to-Buy or Service-at-Shop inventory disappear.
  static List<Widget> homeSections({
    required CommerceMode? selectedMode,
    required void Function(MarketplaceCard card) onCardTap,
    required void Function(MarketplaceCard card) onAdd,
  }) => [
        const SliverToBoxAdapter(child: _Header()),
        for (final mode in CommerceMode.values)
          if (selectedMode == null || selectedMode == mode)
            SliverToBoxAdapter(
              child: MarketplaceHomeModeSection(
                mode: mode,
                onCardTap: onCardTap,
                onAdd: onAdd,
              ),
            ),
      ];
}

/// One mode-specific, horizontal Customer Home rail.
///
/// Each instance is independently rendered: an empty/error Buy Online rail
/// cannot short-circuit Visit to Buy, Services at Shop, or the later Home
/// slivers. The request is capped at twelve cards and is filtered server-side
/// by both commerce mode and the selected shop.
class MarketplaceHomeModeSection extends ConsumerWidget {
  const MarketplaceHomeModeSection({
    super.key,
    required this.mode,
    required this.onCardTap,
    required this.onAdd,
  });

  final CommerceMode mode;
  final void Function(MarketplaceCard card) onCardTap;
  final void Function(MarketplaceCard card) onAdd;

  String get _title => switch (mode) {
        CommerceMode.buyOnline => 'Buy Online near you',
        CommerceMode.visitToBuy => 'Visit to Buy',
        CommerceMode.serviceAtShop => 'Services at Shop',
      };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final section = ref.watch(marketplaceHomeModeFeedProvider(mode));
    if (section.isLoading) {
      return _ModeSectionMessage(
        title: _title,
        child: const SizedBox(
          height: 56,
          child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      );
    }
    if (section.error != null && section.cards.isEmpty) {
      return _ModeSectionMessage(
        title: _title,
        child: SizedBox(
          height: 92,
          child: Center(
            child: TextButton.icon(
              onPressed: () => ref
                  .read(marketplaceHomeModeFeedProvider(mode).notifier)
                  .retry(),
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Retry this section'),
            ),
          ),
        ),
      );
    }
    if (section.cards.isEmpty) return const SizedBox.shrink();

    final hasMoreIndicator = section.isLoadingMore || section.error != null;
    return _ModeSectionMessage(
      title: _title,
      subtitle: mode == CommerceMode.visitToBuy ? 'In-store products' : null,
      child: SizedBox(
        height: MarketplaceCardTile.carouselHeight(context),
        child: NotificationListener<ScrollNotification>(
          onNotification: (notification) {
            if (notification.metrics.axis == Axis.horizontal &&
                notification.metrics.extentAfter < 220 &&
                section.error == null) {
              ref.read(marketplaceHomeModeFeedProvider(mode).notifier).loadMore();
            }
            return false;
          },
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            itemCount: section.cards.length + (hasMoreIndicator ? 1 : 0),
            separatorBuilder: (_, __) => const SizedBox(width: 10),
            itemBuilder: (context, index) {
              if (index >= section.cards.length) {
                return SizedBox(
                  width: 92,
                  child: Center(
                    child: section.isLoadingMore
                        ? const CircularProgressIndicator(strokeWidth: 2)
                        : TextButton(
                            onPressed: () => ref
                                .read(marketplaceHomeModeFeedProvider(mode).notifier)
                                .retry(),
                            child: const Text('Retry'),
                          ),
                  ),
                );
              }
              final card = section.cards[index];
              return SizedBox(
                width: 176,
                child: MarketplaceCardTile(
                  key: ValueKey<String>(card.feedKey),
                  card: card,
                  onTap: () => onCardTap(card),
                  onAdd: card.addable ? () => onAdd(card) : null,
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

/// The pageable continuation of the three short Home rails. It is part of
/// the same CustomScrollView and therefore shares its only vertical viewport.
/// Each API request is bounded; scrolling close to the end loads one more
/// page without dropping the selected shop filter.
class MarketplaceAllProductsSliver extends ConsumerWidget {
  const MarketplaceAllProductsSliver({
    super.key,
    required this.onCardTap,
    required this.onAdd,
  });

  final void Function(MarketplaceCard card) onCardTap;
  final void Function(MarketplaceCard card) onAdd;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (ref.watch(deliveryPinProvider) == null) return const SliverToBoxAdapter(child: SizedBox.shrink());
    final feed = ref.watch(marketplaceHomeAllFeedProvider);
    final children = <Widget>[
      const SliverToBoxAdapter(child: _AllProductsHeader()),
    ];

    if (feed.isLoading && feed.cards.isEmpty) {
      children.add(const SliverToBoxAdapter(
        child: Padding(
          padding: EdgeInsets.fromLTRB(16, 4, 16, 20),
          child: SizedBox(
            height: 56,
            child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
          ),
        ),
      ));
    } else if (feed.error != null && feed.cards.isEmpty) {
      children.add(SliverToBoxAdapter(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: TextButton.icon(
            onPressed: () => ref.read(marketplaceHomeAllFeedProvider.notifier).retry(),
            icon: const Icon(Icons.refresh_rounded),
            label: const Text("Couldn't load nearby listings. Retry"),
          ),
        ),
      ));
    } else if (feed.cards.isEmpty) {
      children.add(const SliverToBoxAdapter(
        child: Padding(
          padding: EdgeInsets.fromLTRB(16, 0, 16, 18),
          child: Text('No nearby products or services found.'),
        ),
      ));
    } else {
      children.add(SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        sliver: SliverGrid(
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 2,
            mainAxisSpacing: 12,
            crossAxisSpacing: 10,
            childAspectRatio: .73,
          ),
          delegate: SliverChildBuilderDelegate(
            (context, index) {
              final card = feed.cards[index];
              return MarketplaceCardTile(
                key: ValueKey<String>('all:${card.feedKey}'),
                card: card,
                onTap: () => onCardTap(card),
                onAdd: card.addable ? () => onAdd(card) : null,
              );
            },
            childCount: feed.cards.length,
          ),
        ),
      ));
      if (feed.error != null || feed.isLoadingMore || feed.hasNext) {
        children.add(SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Center(
              child: feed.isLoadingMore
                  ? const SizedBox.square(dimension: 22, child: CircularProgressIndicator(strokeWidth: 2))
                  : feed.error != null
                      ? TextButton(
                          onPressed: () => ref.read(marketplaceHomeAllFeedProvider.notifier).retry(),
                          child: const Text('Retry nearby listings'),
                        )
                      : const SizedBox(height: 8),
            ),
          ),
        ));
      }
    }
    return SliverMainAxisGroup(slivers: children);
  }
}

class _AllProductsHeader extends StatelessWidget {
  const _AllProductsHeader();

  @override
  Widget build(BuildContext context) => const Padding(
        padding: EdgeInsets.fromLTRB(16, 24, 16, 12),
        child: Text('All nearby products and services',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
      );
}

class _ModeSectionMessage extends StatelessWidget {
  const _ModeSectionMessage({
    required this.title,
    required this.child,
    this.subtitle,
  });

  final String title;
  final String? subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(title,
                      key: ValueKey<String>('marketplace-section-$title'),
                      style: Theme.of(context)
                          .textTheme
                          .titleMedium
                          ?.copyWith(fontWeight: FontWeight.w700)),
                ),
                if (subtitle != null)
                  Text(subtitle!,
                      style: const TextStyle(
                          fontSize: 12, color: AppColors.textSecondary)),
              ],
            ),
          ),
          child,
        ],
      );
}

class _Header extends ConsumerWidget {
  const _Header();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mode = ref.watch(marketplaceModeFilterProvider);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 12),
      child: Row(
        children: [
          Container(
            width: 4,
            height: 20,
            decoration: BoxDecoration(
              color: AppColors.highlight,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              mode == null ? 'Nearby marketplace' : mode.label,
              style: Theme.of(context)
                  .textTheme
                  .titleMedium
                  ?.copyWith(fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
    );
  }
}
