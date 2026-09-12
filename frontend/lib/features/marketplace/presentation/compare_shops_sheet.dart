import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/marketplace/marketplace_models.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/marketplace/shop_context.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';

/// COMPARE OTHER SHOPS: the same item, priced by everybody in reach.
///
/// FINAL PAYABLE IS THE HEADLINE NUMBER, and that is the whole point. ₹100
/// with ₹20 delivery is dearer than ₹80 with ₹10, and a comparison on the
/// shelf price alone gets it exactly backwards. The delivery charge is on the
/// row, next to the price, rather than waiting until checkout to appear.
///
/// TWO MODES, BOTH THE CUSTOMER'S CHOICE. "Best deal" orders by what this
/// purchase would cost; "My shops" puts the shops they have already chosen
/// first. Neither is a default the other has to argue with, and the platform
/// never silently swaps one for the other because a cheaper shop turned up.
///
/// NOTHING IS RANKED BY WHAT A SHOP PAYS GP-STORE. There is no such field on
/// the wire and no such ordering here; the two orderings available are price
/// and the customer's own preference.
class CompareShopsSheet extends ConsumerStatefulWidget {
  const CompareShopsSheet({
    super.key,
    required this.variantId,
    this.categoryId,
    this.productName,
  });

  final int variantId;

  /// Needed for "My shops": preferences are per category, so without one
  /// there is nothing to be preferred about and only Best Deal is offered.
  final int? categoryId;
  final String? productName;

  /// Opens the sheet. Kept here so every caller gets the same presentation.
  static Future<void> show(
    BuildContext context, {
    required int variantId,
    int? categoryId,
    String? productName,
  }) {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) => CompareShopsSheet(
        variantId: variantId,
        categoryId: categoryId,
        productName: productName,
      ),
    );
  }

  @override
  ConsumerState<CompareShopsSheet> createState() => _CompareShopsSheetState();
}

enum _Mode { bestDeal, myShops }

class _CompareShopsSheetState extends ConsumerState<CompareShopsSheet> {
  _Mode _mode = _Mode.bestDeal;

  @override
  Widget build(BuildContext context) {
    final canPrefer = widget.categoryId != null;

    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.75,
      maxChildSize: 0.95,
      builder: (context, scrollController) {
        return Column(
          children: [
            const SizedBox(height: 8),
            Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.textSecondary.withValues(alpha: 0.3),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Compare other shops',
                      style: TextStyle(fontWeight: FontWeight.w800, fontSize: 17)),
                  if ((widget.productName ?? '').isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text(widget.productName!,
                        style: const TextStyle(
                            fontSize: 13, color: AppColors.textSecondary)),
                  ],
                  const SizedBox(height: 4),
                  const Text(
                    'Prices below are what you would actually pay, delivery included.',
                    style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
                  ),
                ],
              ),
            ),
            if (canPrefer)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: SegmentedButton<_Mode>(
                  segments: const [
                    ButtonSegment(value: _Mode.bestDeal, label: Text('Best deal')),
                    ButtonSegment(value: _Mode.myShops, label: Text('My shops')),
                  ],
                  selected: {_mode},
                  onSelectionChanged: (selection) =>
                      setState(() => _mode = selection.first),
                ),
              ),
            const SizedBox(height: 8),
            Expanded(
              child: _mode == _Mode.bestDeal || !canPrefer
                  ? _BestDealList(
                      variantId: widget.variantId,
                      categoryId: widget.categoryId,
                      scrollController: scrollController,
                    )
                  : _MyShopsList(
                      variantId: widget.variantId,
                      categoryId: widget.categoryId!,
                      scrollController: scrollController,
                    ),
            ),
          ],
        );
      },
    );
  }
}

class _BestDealList extends ConsumerWidget {
  const _BestDealList({
    required this.variantId,
    required this.categoryId,
    required this.scrollController,
  });

  final int variantId;
  final int? categoryId;
  final ScrollController scrollController;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(shopComparisonProvider(variantId));

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, _) => _Failed(
        message: extractErrorMessage(error),
        onRetry: () => ref.invalidate(shopComparisonProvider(variantId)),
      ),
      data: (comparison) {
        if (comparison.offers.isEmpty) {
          return const _Empty(
            message: 'No other shop near you is selling this right now.',
          );
        }
        return ListView(
          controller: scrollController,
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          children: [
            _WorthGoingFarther(comparison: comparison),
            for (final offer in comparison.offers)
              _OfferTile(
                offer: offer,
                categoryId: categoryId,
                cheapest: offer.shopId == comparison.cheapestShopId,
                nearest: offer.shopId == comparison.nearestShopId,
                preferred: false,
              ),
          ],
        );
      },
    );
  }
}

/// §7, ANSWERED BY THE SERVER RATHER THAN RE-DERIVED HERE.
///
/// Whether a farther shop is cheap enough to be worth leaving the
/// neighbourhood for is a business rule about final customer cost. The app
/// prints the server's verdict and the server's saving; computing the
/// threshold in Dart would let a rounding difference show a customer a
/// recommendation the marketplace never made.
class _WorthGoingFarther extends StatelessWidget {
  const _WorthGoingFarther({required this.comparison});

  final ShopComparison comparison;

  @override
  Widget build(BuildContext context) {
    if (!comparison.fartherSellerQualifies || comparison.saving <= 0) {
      return const SizedBox.shrink();
    }
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.secondary.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          const Icon(Icons.savings_outlined, size: 18, color: AppColors.secondary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'A shop farther away is ₹${comparison.saving.toStringAsFixed(0)} '
              'cheaper in total, delivery included.',
              style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

class _MyShopsList extends ConsumerWidget {
  const _MyShopsList({
    required this.variantId,
    required this.categoryId,
    required this.scrollController,
  });

  final int variantId;
  final int categoryId;
  final ScrollController scrollController;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = (variantId: variantId, categoryId: categoryId);
    final async = ref.watch(preferredFirstOffersProvider(key));

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, _) => _Failed(
        message: extractErrorMessage(error),
        onRetry: () => ref.invalidate(preferredFirstOffersProvider(key)),
      ),
      data: (result) {
        if (result.offers.isEmpty) {
          return const _Empty(
            message: 'No shop near you is selling this right now.',
          );
        }
        return ListView(
          controller: scrollController,
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          children: [
            // THE HONEST LABEL FOR AN EMPTY PREFERENCE. Without this a
            // customer would read distance order as "these are my shops" and
            // wonder why they never chose them.
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(
                result.hasPreference
                    ? 'Your chosen shops come first. The others are still here, '
                        'and you can buy from any of them.'
                    : "You haven't chosen shops for this category yet, so these "
                        'are in distance order. Tap a star to choose one.',
                style: const TextStyle(fontSize: 12.5, color: AppColors.textSecondary),
              ),
            ),
            for (final offer in result.offers)
              _OfferTile(
                offer: offer,
                categoryId: categoryId,
                cheapest: false,
                nearest: false,
                preferred: result.preferredShopIds.contains(offer.shopId),
              ),
          ],
        );
      },
    );
  }
}

class _OfferTile extends ConsumerWidget {
  const _OfferTile({
    required this.offer,
    required this.categoryId,
    required this.cheapest,
    required this.nearest,
    required this.preferred,
  });

  final ShopOffer offer;
  final int? categoryId;
  final bool cheapest;
  final bool nearest;
  final bool preferred;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final current = ref.watch(shopContextProvider) == offer.shopId;

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(12),
        border: current ? Border.all(color: AppColors.primary, width: 1.4) : null,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  offer.shopName ?? 'Shop ${offer.shopId}',
                  style: const TextStyle(fontWeight: FontWeight.w700),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (categoryId != null)
                IconButton(
                  visualDensity: VisualDensity.compact,
                  tooltip: preferred
                      ? 'Remove from my shops'
                      : 'Add to my shops for this category',
                  icon: Icon(
                    preferred ? Icons.star_rounded : Icons.star_border_rounded,
                    color: preferred ? AppColors.gold : AppColors.textSecondary,
                  ),
                  onPressed: hapticize(() => _togglePreferred(context, ref)),
                ),
            ],
          ),
          const SizedBox(height: 2),
          // THE PRICE THAT MATTERS, AND WHAT IT IS MADE OF. A total with no
          // breakdown under it is a number a customer cannot check.
          _Money(offer: offer),
          const SizedBox(height: 6),
          Text(_where(offer),
              style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
          if (!offer.isBuyableNow) ...[
            const SizedBox(height: 4),
            Text(
              _whyNot(offer),
              style: const TextStyle(
                  fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.error),
            ),
          ],
          const SizedBox(height: 8),
          Row(
            children: [
              if (cheapest)
                const _Tag(text: 'Best deal', color: AppColors.secondary),
              if (nearest && !cheapest)
                const _Tag(text: 'Closest', color: AppColors.primary),
              const Spacer(),
              if (!current)
                TextButton(
                  onPressed: offer.isBuyableNow
                      ? hapticize(() {
                          // SWITCHING IS THE CUSTOMER'S ACT. Nothing here
                          // moves the item for them - §15 is explicit that
                          // the system may say where else it is and must
                          // leave the decision alone.
                          ref.read(shopSwitchProvider).select(offer.shopId);
                          Navigator.of(context).pop();
                        })
                      : null,
                  child: const Text('Shop here'),
                ),
              if (current)
                const Text('You are shopping here',
                    style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: AppColors.primary)),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _togglePreferred(BuildContext context, WidgetRef ref) async {
    final category = categoryId;
    if (category == null) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      // The preferred-first list watches the preferences, so saving is all
      // this has to do - see preferredFirstOffersProvider.
      await ref.read(preferredShopsEditorProvider).toggle(category, offer.shopId);
    } catch (error) {
      // THE CAP IS THE SERVER'S and it says so in its own words - a third
      // shop is refused there, not here, and the refusal is what the customer
      // is shown.
      messenger.showSnackBar(SnackBar(content: Text(extractErrorMessage(error))));
    }
  }

  static String _where(ShopOffer offer) {
    final parts = <String>[];
    if (offer.distanceKm != null) {
      parts.add('${offer.distanceKm!.toStringAsFixed(1)} km away');
    }
    if (offer.ratingCount > 0) {
      parts.add('${offer.ratingAverage.toStringAsFixed(1)}★ (${offer.ratingCount})');
    }
    if (offer.trusted) parts.add('Trusted');
    if (offer.estimatedDeliveryDate != null) {
      parts.add('est. ${offer.estimatedDeliveryDate}');
    }
    return parts.isEmpty ? 'Details unavailable' : parts.join(' · ');
  }

  /// Why this shop cannot sell it right now, in the customer's terms.
  ///
  /// The same five conditions the server applies, reported in the order a
  /// customer would ask them.
  static String _whyNot(ShopOffer offer) {
    if (!offer.listed) return "This shop doesn't stock this item";
    if (!offer.inStock) return 'Out of stock here';
    if (!offer.deliversHere) return "Doesn't deliver to your address";
    if (!offer.acceptingOrders) return 'Not taking orders right now';
    return 'Price unavailable';
  }
}

/// What the customer would actually pay, and what it is made of.
///
/// NO PRICE ON AN EMPTY SHELF (Part 2 §10). A shop with nothing to sell shows
/// words, not a rupee sign with nothing after it - and never ₹0, which would
/// win every comparison it appeared in.
class _Money extends StatelessWidget {
  const _Money({required this.offer});

  final ShopOffer offer;

  @override
  Widget build(BuildContext context) {
    final total = offer.finalPayable;
    if (total == null) {
      return const Text('No price',
          style: TextStyle(
              fontWeight: FontWeight.w700,
              fontSize: 15,
              color: AppColors.textSecondary));
    }

    final parts = <String>[];
    if (offer.sellingPrice != null) {
      parts.add('₹${offer.sellingPrice!.toStringAsFixed(0)} item');
    }
    if ((offer.discount ?? 0) > 0) {
      parts.add('-₹${offer.discount!.toStringAsFixed(0)} off');
    }
    // ZERO IS A LIE WHEN NOBODY QUOTED IT. A shop whose delivery charge could
    // not be worked out must not be drawn as free delivery.
    parts.add(offer.deliveryChargeKnown
        ? '+₹${(offer.deliveryCharge ?? 0).toStringAsFixed(0)} delivery'
        : 'delivery quoted at checkout');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('₹${total.toStringAsFixed(0)}',
            style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 19)),
        Text(parts.join('  ·  '),
            style: const TextStyle(fontSize: 11.5, color: AppColors.textSecondary)),
      ],
    );
  }
}

class _Tag extends StatelessWidget {
  const _Tag({required this.text, required this.color});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(text,
          style: TextStyle(
              fontSize: 11, fontWeight: FontWeight.w700, color: color)),
    );
  }
}

class _Empty extends StatelessWidget {
  const _Empty({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Text(message,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppColors.textSecondary)),
      ),
    );
  }
}

class _Failed extends StatelessWidget {
  const _Failed({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text("Couldn't compare shops: $message", textAlign: TextAlign.center),
            const SizedBox(height: 8),
            TextButton(onPressed: hapticize(onRetry), child: const Text('Retry')),
          ],
        ),
      ),
    );
  }
}
