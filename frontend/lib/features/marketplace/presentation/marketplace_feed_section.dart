import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
/// Slivers rather than a widget, for the reason the shop feed already
/// documented: a nested scrollable with shrinkWrap has no viewport to cull
/// against and builds every tile at once.
class MarketplaceFeedSlivers {
  const MarketplaceFeedSlivers._();

  static List<Widget> build(
    BuildContext context,
    WidgetRef ref, {
    required AsyncValue<MarketplaceFeedState> feed,
    required void Function(MarketplaceCard card) onCardTap,
    required void Function(MarketplaceCard card) onAdd,
  }) {
    return [
      const SliverToBoxAdapter(child: _Header()),
      feed.when(
        loading: () => const SliverToBoxAdapter(
          child: Padding(
            padding: EdgeInsets.symmetric(vertical: 32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                CircularProgressIndicator(strokeWidth: 2),
                SizedBox(height: 12),
                Text('Finding products and services near you…'),
              ],
            ),
          ),
        ),
        error: (error, _) => SliverToBoxAdapter(
          child: _Message(
            icon: Icons.wifi_off_rounded,
            title: "Couldn't load nearby marketplace",
            action: 'Try again',
            onAction: () => ref.invalidate(marketplaceFeedProvider),
          ),
        ),
        data: (state) {
          // TWO DIFFERENT EMPTIES, and telling them apart is the difference
          // between a customer who can act and one who is stuck. No address
          // means nobody can work out which shops reach them, and the fix is
          // one tap. Nothing nearby means the marketplace has not reached
          // their area yet, and no amount of tapping changes that.
          if (state.needsAddress) {
            return const SliverToBoxAdapter(
              child: _Message(
                icon: Icons.location_off_outlined,
                title: 'Add your address to see what is nearby',
                body: 'Shops deliver within their own area, so GP-STORE needs '
                    'to know where you are before it can show you theirs.',
              ),
            );
          }
          if (state.isEmpty) {
            return const SliverToBoxAdapter(
              child: _Message(
                icon: Icons.storefront_outlined,
                title: 'No nearby products or services found',
                body: 'Try again later or browse the nearby shops list.',
              ),
            );
          }
          return SliverToBoxAdapter(
            child: _ModeSections(
              state: state,
              selectedMode: ref.watch(marketplaceModeFilterProvider),
              onCardTap: onCardTap,
              onAdd: onAdd,
            ),
          );
        },
      ),
      SliverToBoxAdapter(
        child: feed.maybeWhen(
          data: (state) => _Footer(state: state),
          orElse: () => const SizedBox(height: 24),
        ),
      ),
    ];
  }
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

class _ModeSections extends StatelessWidget {
  const _ModeSections({
    required this.state,
    required this.selectedMode,
    required this.onCardTap,
    required this.onAdd,
  });

  final MarketplaceFeedState state;
  final CommerceMode? selectedMode;
  final void Function(MarketplaceCard card) onCardTap;
  final void Function(MarketplaceCard card) onAdd;

  @override
  Widget build(BuildContext context) {
    final modes = selectedMode == null
        ? CommerceMode.values
        : [selectedMode!];
    final groups = <Widget>[];
    for (final mode in modes) {
      final cards = state.cards.where((card) => card.commerceMode == mode).toList();
      if (cards.isEmpty) continue;
      final title = switch (mode) {
        CommerceMode.buyOnline => 'Buy Online near you',
        CommerceMode.visitToBuy => 'Visit to Buy',
        CommerceMode.serviceAtShop => 'Services at Shop',
      };
      groups.add(Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
        child: Row(children: [
          Expanded(child: Text(title,
              style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700))),
          if (mode == CommerceMode.visitToBuy)
            const Text('In-store products', style: TextStyle(fontSize: 12, color: AppColors.textSecondary)),
        ]),
      ));
      groups.add(SizedBox(
        height: 292,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          itemCount: cards.length,
          separatorBuilder: (_, __) => const SizedBox(width: 10),
          itemBuilder: (context, index) {
            final card = cards[index];
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
      ));
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: groups);
  }
}

class _Footer extends ConsumerWidget {
  const _Footer({required this.state});

  final MarketplaceFeedState state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (state.error != null) {
      return _Message(
        icon: Icons.refresh_rounded,
        title: 'Could not load more',
        action: 'Try again',
        onAction: () =>
            ref.read(marketplaceFeedProvider.notifier).retryLoadMore(),
      );
    }
    if (state.isLoadingMore) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 24),
        child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
      );
    }
    return const SizedBox(height: 24);
  }
}

class _Message extends StatelessWidget {
  const _Message({
    required this.icon,
    required this.title,
    this.body,
    this.action,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String? body;
  final String? action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 40),
      child: Column(
        children: [
          Icon(icon, size: 36, color: AppColors.textSecondary),
          const SizedBox(height: 12),
          Text(
            title,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
            ),
          ),
          if (body != null) ...[
            const SizedBox(height: 6),
            Text(
              body!,
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 12.5, height: 1.4, color: AppColors.textSecondary),
            ),
          ],
          if (action != null && onAction != null) ...[
            const SizedBox(height: 14),
            OutlinedButton(onPressed: onAction, child: Text(action!)),
          ],
        ],
      ),
    );
  }
}
