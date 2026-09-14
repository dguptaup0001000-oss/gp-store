import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../address/presentation/address_list_screen.dart';
import '../../address/presentation/address_providers.dart';
import 'discovery_widgets.dart';
import 'market_shop_card.dart';

/// The shops near you that sell one category.
///
/// THE SCREEN THIS MARKETPLACE WAS MISSING. Tapping "Medicine" used to open a
/// list of medicine - the products of whichever single shop the app happened
/// to be acting for - which is the right answer for a grocery app with one
/// kirana behind it and the wrong one for a marketplace. What a customer
/// wants first is not a strip of paracetamol, it is which chemist near them
/// is open.
///
/// NOT EVERY CATEGORY IS A GROCERY SHOP (§5). Kirana opens kiranas, Food opens
/// restaurants, Medicine opens chemists, Saree opens saree shops - and none of
/// that is special-cased here, because none of it is this screen's decision. A
/// shop appears under a category when it is listing something orderable in it,
/// so the merchant types sort themselves out and a category added next year
/// works on the day its first merchant lists a product.
///
/// EVERY RANKING DECISION IS THE SERVER'S: which shops serve the pin, which of
/// them stock this category, what order they come in, and when to climb to the
/// next rung of the radius ladder. Nothing here re-sorts or re-filters.
class CategoryShopsScreen extends ConsumerWidget {
  const CategoryShopsScreen({
    super.key,
    required this.categoryId,
    required this.categoryName,
  });

  final int categoryId;
  final String categoryName;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pin = ref.watch(deliveryPinProvider);

    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(categoryName,
                style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 17)),
            const Text('Shops near you',
                style: TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w500,
                    color: AppColors.textSecondary)),
          ],
        ),
      ),
      body: pin == null ? const _NeedAnAddress() : _Shops(categoryId: categoryId),
    );
  }
}

class _Shops extends ConsumerWidget {
  const _Shops({required this.categoryId});

  final int categoryId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pageAsync = ref.watch(categoryShopsProvider(categoryId));

    return pageAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, _) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text("Couldn't load shops: ${extractErrorMessage(error)}"),
            const SizedBox(height: 8),
            TextButton(
              onPressed: hapticize(
                  () => ref.invalidate(categoryShopsProvider(categoryId))),
              child: const Text('Retry'),
            ),
          ],
        ),
      ),
      data: (page) {
        if (page.shops.isEmpty) {
          return NothingFoundHere(
            page: page,
            title: 'No shop near you sells this yet',
            body: 'Every shop sets how far it will deliver, and none of the '
                'ones that reach this address stocks this category at the '
                'moment.',
            also: OutlinedButton(
              onPressed: hapticize(() => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const AddressListScreen()),
                  )),
              child: const Text('Choose another address'),
            ),
          );
        }
        return RefreshIndicator(
          onRefresh: () async => ref.invalidate(categoryShopsProvider(categoryId)),
          // ListView.separated, lazily built: §45 puts no cap on how many
          // shops a category may have, and a Column of all of them would build
          // every card in a dense city before drawing the first.
          child: ListView.separated(
            padding: const EdgeInsets.all(16),
            // One row at the top for the widened notice, one at the bottom for
            // "search farther" - both draw nothing unless the server said
            // there is something to draw.
            itemCount: page.shops.length + 2,
            separatorBuilder: (_, __) => const SizedBox(height: 10),
            itemBuilder: (context, index) {
              if (index == 0) return WidenedNotice(page: page);
              if (index == page.shops.length + 1) return SearchFarther(page: page);
              return MarketShopCard(
                shop: page.shops[index - 1],
                nearest: index == 1,
              );
            },
          ),
        );
      },
    );
  }
}

/// Shops are ordered by distance from somewhere, and there is no somewhere yet.
class _NeedAnAddress extends ConsumerWidget {
  const _NeedAnAddress();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.location_off_outlined,
                size: 44, color: AppColors.textSecondary),
            const SizedBox(height: 12),
            const Text('Add a delivery address first',
                style: TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            const Text(
              'Shops are listed by how far they are from where you want your '
              'order delivered, so we need an address before we can show them.',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: hapticize(() async {
                await Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const AddressListScreen()),
                );
                ref.invalidate(myAddressesProvider);
              }),
              child: const Text('Add an address'),
            ),
          ],
        ),
      ),
    );
  }
}
