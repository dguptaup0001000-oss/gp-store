import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/images/gp_network_image.dart';
import '../../../core/marketplace/marketplace_models.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/marketplace/shop_context.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';

/// One shop, as a customer deciding whether to buy from it sees it.
///
/// THE SCREEN THE DECISION IS MADE ON. A row in a list can carry a name and a
/// distance; whether to trust a kirana with money needs more than that - what
/// customers have said lately, what GP-STORE has actually checked, when the
/// shop is open, how far it comes, and what it promises if something has to
/// go back. All of it is already on `/api/marketplace/shops/{id}`; until this
/// screen existed, none of it reached the customer.
///
/// NOTHING PRIVATE IS DRAWN HERE. The merchant's governance record, their
/// warnings, their appeals and their earnings are between that shopkeeper and
/// GP-STORE. This screen reads the public storefront response and has no
/// access to any of it - which is a property of the endpoint, not a rule this
/// widget is choosing to respect.
class ShopProfileScreen extends ConsumerWidget {
  const ShopProfileScreen({super.key, required this.shopId});

  final int shopId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detailAsync = ref.watch(storefrontProvider(shopId));

    return Scaffold(
      appBar: AppBar(title: const Text('Shop')),
      body: detailAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text("Couldn't open this shop: ${extractErrorMessage(error)}",
                    textAlign: TextAlign.center),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: hapticize(() => ref.invalidate(storefrontProvider(shopId))),
                  child: const Text('Retry'),
                ),
              ],
            ),
          ),
        ),
        data: (detail) => _Profile(detail: detail),
      ),
    );
  }
}

class _Profile extends ConsumerWidget {
  const _Profile({required this.detail});

  final StorefrontDetail detail;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final shop = detail.shop;
    final current = ref.watch(shopContextProvider);
    final isCurrent = current == shop.shopId;

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _Header(detail: detail),
              const SizedBox(height: 16),
              _RatingBlock(rating: detail.rating),
              const SizedBox(height: 16),
              _OpeningBlock(shop: shop),
              const SizedBox(height: 16),
              _DeliveryBlock(shop: shop),
              if (detail.policies.isNotEmpty) ...[
                const SizedBox(height: 16),
                _PoliciesBlock(policies: detail.policies),
              ],
              if ((shop.supportPhone ?? '').isNotEmpty) ...[
                const SizedBox(height: 16),
                _Section(
                  title: 'Contact',
                  child: Text(shop.supportPhone!,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                ),
              ],
              const SizedBox(height: 24),
            ],
          ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton(
                // SHOPPING HERE IS ALLOWED EVEN WHEN THE SHOP CANNOT DELIVER
                // TO THIS ADDRESS, and that is deliberate: browsing is not
                // ordering. The server refuses the order, with its own
                // reason, and refusing the browse here would mean the
                // customer could never see why they should save a different
                // address.
                onPressed: isCurrent
                    ? null
                    : hapticize(() {
                        ref.read(shopSwitchProvider).select(shop.shopId);
                        Navigator.of(context).pop();
                      }),
                child: Text(isCurrent ? 'You are shopping here' : 'Shop here'),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.detail});

  final StorefrontDetail detail;

  @override
  Widget build(BuildContext context) {
    final shop = detail.shop;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 56,
          height: 56,
          child: GpNetworkImage(
            url: shop.logoUrl,
            renderWidth: 56,
            borderRadius: BorderRadius.circular(10),
            fallbackIcon: Icons.storefront_outlined,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                shop.displayName ?? 'Shop ${shop.shopId}',
                style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 19),
              ),
              // The registered business behind the shopfront name, when the
              // server sends one. A customer about to hand over money is
              // entitled to know who they are paying.
              if ((detail.businessName ?? '').isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(detail.businessName!,
                    style: const TextStyle(
                        fontSize: 12.5, color: AppColors.textSecondary)),
              ],
              const SizedBox(height: 8),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  if ((shop.verificationBadge ?? '').isNotEmpty)
                    _Chip(
                      label: shop.verificationBadge!,
                      color: AppColors.primary,
                      icon: Icons.verified_outlined,
                    ),
                  // TRUSTED IS EARNED, NOT GRANTED, and it is computed from
                  // this shop's own trading record every time it is asked.
                  // There is no column behind it, which is what makes it
                  // unpurchasable rather than merely expensive.
                  if (detail.trusted)
                    const _Chip(
                      label: 'Trusted shop',
                      color: AppColors.secondary,
                      icon: Icons.workspace_premium_outlined,
                    ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({required this.label, required this.color, required this.icon});

  final String label;
  final Color color;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 13, color: color),
          const SizedBox(width: 4),
          Text(label,
              style: TextStyle(
                  fontSize: 11.5, fontWeight: FontWeight.w700, color: color)),
        ],
      ),
    );
  }
}

/// What customers have actually said.
///
/// LIFETIME AND RECENT ARE BOTH SHOWN, because a shop that was good two years
/// ago and is poor now has a flattering lifetime average and a truthful recent
/// one, and only showing the first would mislead the customer in the exact
/// case the second exists for.
///
/// AN UNRATED SHOP SAYS SO. Zero is not three: a new kirana has no rating, and
/// drawing half the stars for it would invent a number nobody gave.
class _RatingBlock extends StatelessWidget {
  const _RatingBlock({required this.rating});

  final ShopRatingSummary? rating;

  @override
  Widget build(BuildContext context) {
    final r = rating;
    if (r == null || !r.hasRating) {
      return const _Section(
        title: 'Ratings',
        child: Text(
          'Not rated yet. This shop has no customer ratings so far.',
          style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
        ),
      );
    }

    return _Section(
      title: 'Ratings',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.star_rounded, color: AppColors.gold, size: 22),
              const SizedBox(width: 4),
              Text(r.average.toStringAsFixed(1),
                  style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 20)),
              const SizedBox(width: 8),
              Text('${r.count} rating${r.count == 1 ? '' : 's'}',
                  style: const TextStyle(color: AppColors.textSecondary, fontSize: 13)),
            ],
          ),
          if (r.recentCount > 0) ...[
            const SizedBox(height: 6),
            Text(
              'Last ${r.recentDays} days: ${r.recentAverage.toStringAsFixed(1)} '
              'from ${r.recentCount} rating${r.recentCount == 1 ? '' : 's'}',
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
            ),
          ],
          if (r.verifiedCount > 0) ...[
            const SizedBox(height: 4),
            Text(
              '${r.verifiedCount} from customers who ordered from this shop',
              style: const TextStyle(color: AppColors.textSecondary, fontSize: 12.5),
            ),
          ],
          if (r.topReasons.isNotEmpty) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: r.topReasons
                  .where((reason) => (reason.reason ?? '').isNotEmpty)
                  .map((reason) => _Chip(
                        label: '${_readable(reason.reason!)} (${reason.count})',
                        color: reason.praise ? AppColors.secondary : AppColors.accent,
                        icon: reason.praise
                            ? Icons.thumb_up_outlined
                            : Icons.thumb_down_outlined,
                      ))
                  .toList(),
            ),
          ],
        ],
      ),
    );
  }

  /// REASON CODES ARE SHOUTED CONSTANTS ON THE WIRE. Lower-casing and
  /// unscoring them is presentation, not translation - an unknown code still
  /// renders as readable words rather than as a blank chip.
  static String _readable(String code) =>
      code.replaceAll('_', ' ').toLowerCase();
}

/// When this shop is open, and when it is not.
class _OpeningBlock extends StatelessWidget {
  const _OpeningBlock({required this.shop});

  final Storefront shop;

  @override
  Widget build(BuildContext context) {
    final lines = <String>[];
    if (shop.closedToday) {
      final reason = shop.closureReason;
      lines.add((reason == null || reason.isEmpty)
          ? 'Closed today.'
          : 'Closed today - $reason');
    } else if (!shop.openNow) {
      lines.add('Closed right now.');
    } else {
      lines.add('Open now.');
    }

    if (!shop.acceptingOrders) {
      final until = shop.pausedUntil;
      lines.add(until == null
          ? 'Not taking new orders at the moment.'
          : 'Paused until $until.');
    }
    // BROWSING IS NEVER CLOSED, and saying so stops a shut shop reading as a
    // broken one.
    lines.add('You can look around a shop even when it is shut.');

    return _Section(
      title: 'Opening',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final line in lines)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(line, style: const TextStyle(fontSize: 13)),
            ),
        ],
      ),
    );
  }
}

/// How far this shop comes, and when the order would arrive.
///
/// AN ESTIMATE, AND LABELLED AS ONE. GP-STORE does not control this shop's
/// delivery - who drives, how many trips, which route and in what order are
/// all the merchant's. The date below is the shop's own schedule, not a
/// promise the platform is making on its behalf.
class _DeliveryBlock extends StatelessWidget {
  const _DeliveryBlock({required this.shop});

  final Storefront shop;

  @override
  Widget build(BuildContext context) {
    return _Section(
      title: 'Delivery',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (shop.distanceKm != null)
            Text('${shop.distanceKm!.toStringAsFixed(1)} km from your address',
                style: const TextStyle(fontSize: 13)),
          if (shop.maxDeliveryRadiusKm != null)
            Text('Delivers up to ${shop.maxDeliveryRadiusKm!.toStringAsFixed(0)} km',
                style: const TextStyle(fontSize: 13)),
          if (shop.deliversHere == false)
            const Padding(
              padding: EdgeInsets.only(top: 4),
              child: Text(
                "This shop doesn't deliver to your saved address.",
                style: TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.error),
              ),
            ),
          if (shop.nextDeliveryDate != null)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text('Next delivery day: ${shop.nextDeliveryDate}',
                  style: const TextStyle(fontSize: 13)),
            ),
          const SizedBox(height: 6),
          const Text(
            'Delivery is arranged by the shop itself. Any time given is the '
            "shop's own estimate, not a GP-STORE guarantee.",
            style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
          ),
        ],
      ),
    );
  }
}

/// The shop's own promises, in the shop's own words.
class _PoliciesBlock extends StatelessWidget {
  const _PoliciesBlock({required this.policies});

  final List<ShopPolicy> policies;

  @override
  Widget build(BuildContext context) {
    return _Section(
      title: 'This shop says',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final policy in policies)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(_title(policy.kind),
                      style: const TextStyle(
                          fontWeight: FontWeight.w700, fontSize: 13)),
                  const SizedBox(height: 2),
                  Text(policy.body ?? '',
                      style: const TextStyle(
                          fontSize: 13, color: AppColors.textSecondary)),
                ],
              ),
            ),
        ],
      ),
    );
  }

  static String _title(String kind) {
    final words = kind.replaceAll('_', ' ').toLowerCase();
    return words.isEmpty ? words : '${words[0].toUpperCase()}${words.substring(1)}';
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              style: const TextStyle(
                  fontWeight: FontWeight.w800,
                  fontSize: 12,
                  letterSpacing: 0.4,
                  color: AppColors.textSecondary)),
          const SizedBox(height: 8),
          child,
        ],
      ),
    );
  }
}
