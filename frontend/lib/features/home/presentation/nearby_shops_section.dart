import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../marketplace/presentation/market_shop_card.dart';
import '../../marketplace/presentation/shop_picker_screen.dart';
import 'home_load_stage.dart';

/// The shops near this customer, on the home screen.
///
/// THE SECTION THAT SAYS "MARKETPLACE" WITHOUT SAYING IT. A home screen made
/// of products is a shop's home screen however many merchants are behind it;
/// a customer only learns there is a choice when they see the shops. Putting
/// them above the offers and the carousels is the whole argument: discovery
/// first, merchandising second.
///
/// DRAWS NOTHING UNDER A SINGLE SHOP (§14). An existing customer must not have
/// to learn that a multi-shop architecture exists, and "nearby shops" over a
/// list of one is a section that answers a question nobody asked.
///
/// DRAWS NOTHING WITHOUT AN ADDRESS EITHER. Shops are ordered by distance from
/// somewhere; with no somewhere there is no list, and the header already
/// carries "Set your address" as the thing to do about it. A second empty
/// state here would be the same nudge twice.
class NearbyShopsSection extends ConsumerWidget {
  const NearbyShopsSection({super.key, this.howMany = 6});

  /// How many to preview. NOT a cap on how many exist - "See all" opens the
  /// full list, which is itself unbounded and lazily built.
  final int howMany;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // BEHIND THE GATE, with the rest of the below-the-fold work: this is one
    // more request, and under a single shop - which is every deployment today
    // - it can never draw anything.
    if (!ref.watch(homeBelowFoldReadyProvider)) return const SizedBox.shrink();
    if (!ref.watch(isMarketplaceProvider)) return const SizedBox.shrink();

    final pin = ref.watch(deliveryPinProvider);
    if (pin == null) return const SizedBox.shrink();

    final shops = ref.watch(shopsNearProvider(pin)).valueOrNull;
    // Loading and error both draw nothing rather than a spinner or a red
    // strip: this is a discovery aid, not the page, and a customer whose
    // shop list is slow should still get their offers and their feed.
    if (shops == null || shops.isEmpty) return const SizedBox.shrink();

    final preview = shops.take(howMany).toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
          child: Row(
            children: [
              const Expanded(
                child: Text('Shops near you',
                    style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
              ),
              if (shops.length > preview.length)
                TextButton(
                  onPressed: hapticize(() => Navigator.of(context).push(
                        MaterialPageRoute(builder: (_) => const ShopPickerScreen()),
                      )),
                  child: const Text('See all'),
                ),
            ],
          ),
        ),
        SizedBox(
          // Two lines of body text plus the name and the logo. Fixed so the
          // row does not resize when a shop with a longer status note scrolls
          // into view.
          height: 104,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            // Lazily built even at six: the same list becomes twenty in a
            // dense city, and a builder that is lazy at six is lazy at twenty
            // without anybody remembering to change it.
            itemCount: preview.length,
            separatorBuilder: (_, __) => const SizedBox(width: 10),
            itemBuilder: (context, index) => SizedBox(
              // Wide enough for a name, a rating and a distance; narrow enough
              // that the next card peeks, which is what tells a customer the
              // row scrolls.
              width: 260,
              child: MarketShopCard(
                shop: preview[index],
                nearest: index == 0,
                selected: preview[index].shopId == ref.watch(selectedShopIdProvider),
              ),
            ),
          ),
        ),
        const SizedBox(height: 4),
        const Divider(height: 1, color: AppColors.divider),
      ],
    );
  }
}
