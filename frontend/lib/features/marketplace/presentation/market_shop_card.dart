import 'package:flutter/material.dart';

import '../../../core/images/gp_network_image.dart';
import '../../../core/marketplace/marketplace_models.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import 'shop_profile_screen.dart';

/// One shop, as a customer deciding where to buy from sees it.
///
/// ONE CARD FOR EVERY LIST. The shop picker, a category's shops and the home
/// screen's nearby row all draw the same shop, and three cards would drift
/// into three different ideas of what matters about one - which of them shows
/// that it is shut, which shows that it will not deliver here, which rounds
/// the distance differently. A customer who learns to read this card once has
/// learned every list in the app.
///
/// EVERY FIGURE ON IT IS THE SERVER'S. The distance, the radius, the rating,
/// whether it is open, whether it delivers here - all come off the storefront
/// response. Nothing is computed in Dart, because a distance worked out twice
/// is a distance that can disagree with itself.
///
/// IT NEVER SWITCHES SHOPS. Tapping opens the shop's profile, where "Shop
/// here" is a deliberate button; a tile that switched the whole app to a new
/// shop would make the decision for the customer, and switching throws away
/// the basket context they were building.
class MarketShopCard extends StatelessWidget {
  const MarketShopCard({
    super.key,
    required this.shop,
    this.selected = false,
    this.nearest = false,
    this.onTap,
  });

  final Storefront shop;

  /// The shop the app is currently acting for.
  final bool selected;

  /// Closest of the list it is being drawn in.
  final bool nearest;

  /// Defaults to opening the shop's profile. Overridden only where the list
  /// itself is a chooser and the caller has somewhere else to go.
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final shut = whenItIsShut(shop);

    return Material(
      color: AppColors.cardBackground,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: hapticize(onTap ??
            () => Navigator.of(context).push(
                  MaterialPageRoute(
                      builder: (_) => ShopProfileScreen(shopId: shop.shopId)),
                )),
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: selected ? AppColors.primary : AppColors.divider,
              width: selected ? 1.5 : 1,
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _Logo(shop: shop),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            shop.displayName ?? 'Shop ${shop.shopId}',
                            style: const TextStyle(
                                fontWeight: FontWeight.w700, fontSize: 15),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        if (selected)
                          const Icon(Icons.check_circle,
                              color: AppColors.primary, size: 18),
                      ],
                    ),
                    const SizedBox(height: 4),
                    _StarsAndDistance(shop: shop),
                    // WHAT GP-STORE HAS CHECKED, in the server's own words, so
                    // a level added on the server does not render as a blank
                    // chip here.
                    if ((shop.verificationBadge ?? '').isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        shop.verificationBadge!,
                        style: const TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w600,
                          color: AppColors.primary,
                        ),
                      ),
                    ],
                    // A SHOP OUTSIDE ITS OWN RADIUS IS STILL SHOWN, and is told
                    // plainly that it cannot deliver here. Searching farther
                    // widens what the customer can SEE; it widens nothing any
                    // shop promised.
                    if (shop.deliversHere == false)
                      const _Note(
                        text: "Doesn't deliver to this address",
                        color: AppColors.error,
                      ),
                    if (shut != null)
                      _Note(text: shut, color: AppColors.textSecondary),
                    if (nearest && !selected && shop.deliversHere != false)
                      const _Note(
                        text: 'Closest to your address',
                        color: AppColors.secondary,
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Why this shop will not take an order right now, or null when it will.
  ///
  /// BROWSING IS NEVER CLOSED, so none of this hides the shop - it explains
  /// it. "Back at 4pm" and "closed today" are different facts and a customer
  /// deciding where to buy from needs the difference.
  static String? whenItIsShut(Storefront shop) {
    if (shop.closedToday) {
      final reason = shop.closureReason;
      return (reason == null || reason.isEmpty)
          ? 'Closed today'
          : 'Closed today - $reason';
    }
    if (!shop.acceptingOrders) {
      final until = shop.pausedUntil;
      return until == null ? 'Not taking orders right now' : 'Paused until $until';
    }
    if (!shop.openNow) return 'Closed right now';
    return null;
  }
}

/// The stars, the distance and how far this shop will come, on one line.
///
/// SEPARATED BY DOTS RATHER THAN STACKED because all three are the same kind
/// of fact - small, numeric, scanned rather than read - and three lines of
/// them turns a list of six shops into a screen of forty lines.
class _StarsAndDistance extends StatelessWidget {
  const _StarsAndDistance({required this.shop});

  final Storefront shop;

  @override
  Widget build(BuildContext context) {
    final rated = shop.ratingCount > 0 && shop.ratingAverage != null;
    return Row(
      children: [
        if (rated) ...[
          const Icon(Icons.star_rounded, size: 15, color: AppColors.gold),
          const SizedBox(width: 2),
          Text(
            '${shop.ratingAverage!.toStringAsFixed(1)} (${shop.ratingCount})',
            style: const TextStyle(
                fontSize: 12, fontWeight: FontWeight.w600, height: 1.1),
          ),
          const _Dot(),
        ] else ...[
          // NOT ZERO STARS. A new shop nobody has rated is not a bad shop, and
          // drawing 0.0 beside its name would be the app saying so about a
          // real merchant.
          const Text('New',
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: AppColors.secondary,
                  height: 1.1)),
          const _Dot(),
        ],
        Flexible(
          child: Text(
            distanceAndRange(shop),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
                fontSize: 12, color: AppColors.textSecondary, height: 1.1),
          ),
        ),
      ],
    );
  }

  /// How far away it is, and how far it is willing to come.
  ///
  /// Both numbers are the server's. distanceKm is null when the question had
  /// no pin in it, and is rendered as absent rather than as zero.
  static String distanceAndRange(Storefront shop) {
    final parts = <String>[];
    if (shop.distanceKm != null) {
      parts.add('${shop.distanceKm!.toStringAsFixed(1)} km');
    }
    if (shop.maxDeliveryRadiusKm != null) {
      parts.add('delivers to ${shop.maxDeliveryRadiusKm!.toStringAsFixed(0)} km');
    }
    return parts.isEmpty ? 'Delivery details unavailable' : parts.join(' · ');
  }
}

class _Dot extends StatelessWidget {
  const _Dot();

  @override
  Widget build(BuildContext context) => const Padding(
        padding: EdgeInsets.symmetric(horizontal: 5),
        child: Text('·',
            style: TextStyle(fontSize: 12, color: AppColors.textSecondary)),
      );
}

class _Note extends StatelessWidget {
  const _Note({required this.text, required this.color});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Text(
          text,
          style: TextStyle(
              fontSize: 11.5, fontWeight: FontWeight.w600, color: color),
        ),
      );
}

/// The shop's own logo, or its initial.
///
/// AN INITIAL RATHER THAN A GENERIC STOREFRONT ICON, because a list of six
/// shops with the same grey icon beside each is a list with no way to tell
/// them apart at a glance. A merchant who has not uploaded a logo still gets
/// something of their own.
class _Logo extends StatelessWidget {
  const _Logo({required this.shop});

  final Storefront shop;

  @override
  Widget build(BuildContext context) {
    final url = shop.logoUrl;
    final name = (shop.displayName ?? '').trim();
    final initial = name.isEmpty ? '?' : name.characters.first.toUpperCase();

    return ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: SizedBox(
        width: 46,
        height: 46,
        child: (url == null || url.isEmpty)
            ? ColoredBox(
                color: AppColors.tint(AppColors.primary),
                child: Center(
                  child: Text(
                    initial,
                    style: const TextStyle(
                        fontWeight: FontWeight.w800,
                        fontSize: 18,
                        color: AppColors.primary),
                  ),
                ),
              )
            : GpNetworkImage(
                url: url,
                renderWidth: 46,
                fit: BoxFit.cover,
                fallbackIcon: Icons.storefront_outlined,
              ),
      ),
    );
  }
}
