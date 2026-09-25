import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../marketplace/presentation/market_shop_card.dart';
import '../../marketplace/presentation/marketplace_feed_provider.dart';
import '../../marketplace/presentation/nearby_shops_screen.dart';

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
    if (!ref.watch(isMarketplaceProvider)) return const SizedBox.shrink();

    final pin = ref.watch(deliveryPinProvider);
    if (pin == null) return const SizedBox.shrink();

    const previewSize = 7;
    final query = (lat: pin.lat, lng: pin.lng, page: 0, size: previewSize);
    final shopsAsync = ref.watch(nearbyShopsPageProvider(query));
    final selected = ref.watch(marketplaceShopFilterProvider);
    if (shopsAsync.isLoading) {
      return const Padding(
        padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: Row(children: [
          SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
          SizedBox(width: 10),
          Text('Finding shops near you…'),
        ]),
      );
    }
    if (shopsAsync.hasError) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
        child: TextButton.icon(
          onPressed: () => ref.invalidate(nearbyShopsPageProvider(query)),
          icon: const Icon(Icons.refresh),
          label: const Text("Couldn't load nearby shops. Retry"),
        ),
      );
    }
    final page = shopsAsync.valueOrNull;
    final shops = page?.shops ?? const [];
    if (shops.isEmpty) return const SizedBox.shrink();

    final preview = shops.take(howMany).toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
          child: Row(
            children: [
              const Expanded(
                child: Text('All nearby shops',
                    style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
              ),
              if (page?.hasNext == true || shops.length > preview.length)
                TextButton(
                  onPressed: hapticize(() => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => NearbyShopsScreen(latitude: pin.lat, longitude: pin.lng),
                        ),
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
            itemCount: preview.length + 1,
            separatorBuilder: (_, __) => const SizedBox(width: 10),
            itemBuilder: (context, index) => SizedBox(
              // Wide enough for a name, a rating and a distance; narrow enough
              // that the next card peeks, which is what tells a customer the
              // row scrolls.
              width: index == 0 ? 116 : 260,
              child: index == 0
                  ? Material(
                      color: selected == null
                          ? AppColors.primary.withValues(alpha: .10)
                          : AppColors.cardBackground,
                      borderRadius: BorderRadius.circular(14),
                      child: InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => ref.read(marketplaceShopFilterProvider.notifier).state = null,
                        child: Container(
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(14),
                            border: Border.all(color: selected == null ? AppColors.primary : AppColors.divider),
                          ),
                          child: Center(child: Column(mainAxisSize: MainAxisSize.min, children: [
                            Icon(Icons.store_mall_directory_outlined,
                                color: AppColors.primary, size: 28),
                            const SizedBox(height: 7),
                            const Text('All', style: TextStyle(fontWeight: FontWeight.w700)),
                            const Text('nearby shops', style: TextStyle(fontSize: 11)),
                          ])),
                        ),
                      ),
                    )
                  : MarketShopCard(
                shop: preview[index - 1],
                nearest: index == 1,
                selected: preview[index - 1].shopId == selected,
                onTap: () => ref.read(marketplaceShopFilterProvider.notifier).state =
                    preview[index - 1].shopId,
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
