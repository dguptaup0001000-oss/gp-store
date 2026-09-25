import '../../../core/images/gp_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/action_feedback.dart';
import '../../wishlist/presentation/wishlist_providers.dart';
import '../domain/marketplace_feed_models.dart';

/// One marketplace card, drawn for whichever mode it is.
///
/// ONE WIDGET RATHER THAN THREE, because the three modes differ in what they
/// AFFORD rather than in what they are. All three show a picture, a name, a
/// price and who has it; only the ending differs, so only the ending is
/// conditional. Three widgets would drift apart at the parts that should stay
/// the same.
///
/// THE ADD BUTTON IS DRAWN FROM [MarketplaceCard.addable], which is the
/// server's answer. This widget never inspects the mode to decide whether
/// something can be bought - a screen that worked that out for itself would
/// eventually disagree with the backend, and the direction it would disagree
/// in is offering to sell something that cannot be sold.
class MarketplaceCardTile extends ConsumerWidget {
  const MarketplaceCardTile({
    super.key,
    required this.card,
    this.onTap,
    this.onAdd,
  });

  final MarketplaceCard card;
  final VoidCallback? onTap;

  /// Called only when the server said this is addable. Null is fine.
  final VoidCallback? onAdd;

  /// Height for this card's horizontal rail, derived from the image geometry
  /// and text scale so the image and all mode-specific details fit together.
  /// Reserving the extra detail rows covers starting prices and service
  /// duration without relying on a one-size-fits-all viewport height.
  static double carouselHeight(BuildContext context, {double cardWidth = 176}) {
    final scale = MediaQuery.textScalerOf(context).scale(14) / 14;
    const detailsAtScaleOne = 18 + // body padding
        (13 * 1.25 * 2) + // two-line product name
        4 +
        (11 * 1.2) + // shop and distance
        6 +
        (14 * 1.2) + // price
        (9.5 * 1.2) + // confirm-at-shop note
        (9.5 * 1.2) + // service duration
        26; // compact action and breathing room
    return (cardWidth / 1.25) + (detailsAtScaleOne * scale) + 8;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isWishlisted = ref.watch(wishlistControllerProvider).valueOrNull
            ?.any((item) => item.product?.id == card.productId) ??
        false;
    return Material(
      color: AppColors.cardBackground,
      borderRadius: BorderRadius.circular(14),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final cardWidth = constraints.maxWidth.isFinite
                ? constraints.maxWidth
                : 176.0;
            return Column(
              // The horizontal viewport supplies its full rail height as a
              // maximum. Keep each card at its content height instead of
              // stretching it to that viewport: transient/provider updates
              // can otherwise lay out the body against the old rail extent
              // and report a false vertical flex overflow.
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: cardWidth,
                  height: cardWidth / 1.25,
                  child: _Thumbnail(
                    card: card,
                    isWishlisted: isWishlisted,
                    onWishlistTap: () async {
                      try {
                        final added = await ref
                            .read(wishlistControllerProvider.notifier)
                            .toggle(card.productId);
                        if (!context.mounted) return;
                        if (added == null) {
                          showActionFailure(context, "Couldn't update wishlist. Please try again.");
                        } else {
                          showWishlistFeedback(context, added: added);
                        }
                      } catch (_) {
                        if (context.mounted) {
                          showActionFailure(context, "Couldn't update wishlist. Please try again.");
                        }
                      }
                    },
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        card.name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 13,
                          height: 1.25,
                          fontWeight: FontWeight.w600,
                          color: AppColors.textPrimary,
                        ),
                      ),
                      const SizedBox(height: 4),
                      _Provenance(card: card),
                      const SizedBox(height: 6),
                      _PriceAndAction(card: card, onAdd: onAdd, onView: onTap),
                    ],
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _Thumbnail extends StatelessWidget {
  const _Thumbnail({
    required this.card,
    required this.isWishlisted,
    required this.onWishlistTap,
  });

  final MarketplaceCard card;
  final bool isWishlisted;
  final VoidCallback onWishlistTap;

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
          // GpNetworkImage RATHER THAN Image.network, which the repository
          // enforces with a test - and rightly: a raw Image.network caches
          // nothing between scrolls, downloads the full original to draw a
          // thumbnail, and decodes at the file's resolution rather than the
          // screen's. On a grid the customer flicks through, that is the
          // difference between a smooth feed and a hot phone.
          GpNetworkImage.fill(
            url: card.imageUrl,
            fit: BoxFit.cover,
            fallbackIcon: Icons.storefront_outlined,
            fallbackIconSize: 32,
            placeholderColor: AppColors.surfaceSoft,
            placeholderIconColor: AppColors.textSecondary,
          ),
          // THE MODE IS ON THE PICTURE, not buried in the body text. A
          // customer must be able to tell at a glance which of these they can
          // buy now and which means a trip, before they have read a word.
          if (card.commerceMode != CommerceMode.buyOnline)
            Positioned(
              left: 6,
              top: 6,
              child: _ModeBadge(mode: card.commerceMode),
            ),
          Positioned(
            right: 6,
            top: 6,
            child: Material(
              color: Colors.white.withValues(alpha: .94),
              shape: const CircleBorder(),
              child: IconButton(
                tooltip: isWishlisted ? 'Remove from wishlist' : 'Add to wishlist',
                visualDensity: VisualDensity.compact,
                onPressed: onWishlistTap,
                icon: Icon(
                  isWishlisted ? Icons.favorite : Icons.favorite_border,
                  size: 20,
                  color: isWishlisted ? AppColors.error : AppColors.textSecondary,
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _ModeBadge extends StatelessWidget {
  const _ModeBadge({required this.mode});

  final CommerceMode mode;

  @override
  Widget build(BuildContext context) {
    final visit = mode == CommerceMode.visitToBuy;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: (visit ? AppColors.gold : AppColors.secondary).withValues(alpha: 0.94),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(visit ? Icons.storefront_rounded : Icons.build_circle_outlined,
              size: 11, color: Colors.white),
          const SizedBox(width: 3),
          Text(
            mode.label,
            style: const TextStyle(
              fontSize: 9.5,
              height: 1.1,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              letterSpacing: 0.2,
            ),
          ),
        ],
      ),
    );
  }
}

/// Who has it and how far away, which is the thing a local marketplace knows
/// that a catalogue does not.
class _Provenance extends StatelessWidget {
  const _Provenance({required this.card});

  final MarketplaceCard card;

  @override
  Widget build(BuildContext context) {
    final bits = <String>[];
    if (card.shopName != null && card.shopName!.isNotEmpty) {
      bits.add(card.shopName!);
    }
    final distance = card.distanceKm;
    if (distance != null && distance.isFinite) {
      bits.add(distance < 1
          ? '${(distance * 1000).round()} m'
          : '${distance.toStringAsFixed(1)} km');
    }
    if (bits.isEmpty) return const SizedBox.shrink();

    return Row(
      children: [
        Expanded(
          child: Text(
            bits.join(' · '),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 11, color: AppColors.textSecondary),
          ),
        ),
        // Only when there is genuinely a choice to make. "1 shop" is noise.
        if (card.sellerCount > 1)
          Padding(
            padding: const EdgeInsets.only(left: 4),
            child: Text(
              '+${card.sellerCount - 1}',
              style: const TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                color: AppColors.primary,
              ),
            ),
          ),
      ],
    );
  }
}

class _PriceAndAction extends StatelessWidget {
  const _PriceAndAction({required this.card, this.onAdd, this.onView});

  final MarketplaceCard card;
  final VoidCallback? onAdd;
  final VoidCallback? onView;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                card.priceLabel(),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                  color: AppColors.textPrimary,
                ),
              ),
              // SAYING SO WHERE IT IS NOT A PROMISE. A merchant who published
              // "from ₹25,000" has not agreed to that figure, and a card that
              // draws it like a checkout price is making a commitment on their
              // behalf that they will be held to at the counter.
              if (!card.priceIsCommitted)
                const Text(
                  'Confirm at shop',
                  style: TextStyle(fontSize: 9.5, color: AppColors.textSecondary),
                ),
              if (card.serviceDurationMinutes != null)
                Text(
                  '~${card.serviceDurationMinutes} min',
                  style: const TextStyle(
                      fontSize: 9.5, color: AppColors.textSecondary),
                ),
            ],
          ),
        ),
        const SizedBox(width: 6),
        _Action(card: card, onAdd: onAdd, onView: onView),
      ],
    );
  }
}

class _Action extends StatelessWidget {
  const _Action({required this.card, this.onAdd, this.onView});

  final MarketplaceCard card;
  final VoidCallback? onAdd;
  final VoidCallback? onView;

  @override
  Widget build(BuildContext context) {
    // THE ONE CONDITIONAL THAT MATTERS, and it reads the server's flag rather
    // than the mode. A Visit-to-Buy ring and a service both get VIEW; only
    // something the backend says is addable gets ADD.
    if (!card.addable) {
      final label = switch (card.commerceMode) {
        CommerceMode.visitToBuy => 'VISIT SHOP',
        CommerceMode.serviceAtShop => 'VIEW SERVICE',
        CommerceMode.buyOnline => card.inStock == false ? 'SOLD OUT' : 'VIEW',
      };
      return OutlinedButton(
        onPressed: onView,
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.primary,
          padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 5),
          minimumSize: Size.zero,
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          side: const BorderSide(color: AppColors.primary),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
        child: Text(label, style: const TextStyle(
          fontSize: 9.5,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.3,
        )),
      );
    }

    return Material(
      color: AppColors.primary,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onAdd,
        borderRadius: BorderRadius.circular(8),
        child: const Padding(
          padding: EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: Text(
            'ADD',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              letterSpacing: 0.3,
            ),
          ),
        ),
      ),
    );
  }
}
