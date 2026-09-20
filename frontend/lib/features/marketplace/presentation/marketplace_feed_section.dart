import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../domain/marketplace_feed_models.dart';
import 'marketplace_card_tile.dart';
import 'marketplace_feed_provider.dart';

/// The marketplace feed at the foot of the home screen: what is for sale
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
            child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
          ),
        ),
        error: (error, _) => SliverToBoxAdapter(
          child: _Message(
            icon: Icons.wifi_off_rounded,
            title: 'Could not load the marketplace',
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
                title: 'No shops deliver here yet',
                body: 'GP-STORE has not reached your area yet. '
                    'Shops appear here as they join.',
              ),
            );
          }
          return _Grid(state: state, onCardTap: onCardTap, onAdd: onAdd);
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
              mode == CommerceMode.buyOnline ? 'Near you' : mode.label,
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

class _Grid extends StatelessWidget {
  const _Grid({
    required this.state,
    required this.onCardTap,
    required this.onAdd,
  });

  final MarketplaceFeedState state;
  final void Function(MarketplaceCard card) onCardTap;
  final void Function(MarketplaceCard card) onAdd;

  @override
  Widget build(BuildContext context) {
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      sliver: SliverGrid(
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          mainAxisSpacing: 10,
          crossAxisSpacing: 10,
          childAspectRatio: 0.62,
        ),
        delegate: SliverChildBuilderDelegate(
          (context, index) {
            final card = state.cards[index];
            return MarketplaceCardTile(
              // Keyed by product so Flutter can tell two cards apart; a
              // duplicate key is a crash rather than a cosmetic problem,
              // which is why the controller dedupes as well.
              key: ValueKey<int>(card.productId),
              card: card,
              onTap: () => onCardTap(card),
              onAdd: card.addable ? () => onAdd(card) : null,
            );
          },
          childCount: state.cards.length,
        ),
      ),
    );
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
