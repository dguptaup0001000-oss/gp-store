import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/marketplace/shop_context.dart';
import '../../../core/theme/app_theme.dart';
import 'discovery_widgets.dart';
import 'market_shop_card.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../address/presentation/address_list_screen.dart';
import '../../address/presentation/address_providers.dart';

/// Which shop the customer is buying from.
///
/// THE SCREEN THAT MAKES ShopContext.select() REAL. Everything under it -
/// the header on every request, the catalogue, the prices, the store's
/// opening hours, which kirana packs the order - follows from what is chosen
/// here. Until this existed the app simply let the backend pick the nearest
/// shop and the customer had no say.
///
/// THE LIST IS THE BACKEND'S, IN THE BACKEND'S ORDER. ShopDiscovery answers
/// which shops will deliver to this address - each shop's own radius, not a
/// platform-wide one - and returns them nearest first. This screen re-sorts
/// nothing and hides nothing.
class ShopPickerScreen extends ConsumerWidget {
  const ShopPickerScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pin = ref.watch(deliveryPinProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Choose a shop')),
      body: pin == null ? _NoAddress(ref: ref) : _NearbyList(pin: pin),
    );
  }
}

/// A customer with no saved address cannot be shown to be inside anybody's
/// delivery radius, so there is nothing to list. The backend applies exactly
/// this rule - a request with no pin returns an empty list rather than every
/// shop - and this says so in words a customer can act on.
class _NoAddress extends StatelessWidget {
  const _NoAddress({required this.ref});

  final WidgetRef ref;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.location_off_outlined,
                size: 44, color: AppColors.textSecondary),
            const SizedBox(height: 12),
            const Text(
              'Add a delivery address first',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
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

class _NearbyList extends ConsumerWidget {
  const _NearbyList({required this.pin});

  final ({double lat, double lng}) pin;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pageAsync = ref.watch(discoveryProvider(pin));
    final current = ref.watch(shopContextProvider);

    return pageAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, _) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text("Couldn't load shops: ${extractErrorMessage(error)}"),
            const SizedBox(height: 8),
            TextButton(
              onPressed: hapticize(() => ref.invalidate(discoveryProvider(pin))),
              child: const Text('Retry'),
            ),
          ],
        ),
      ),
      data: (page) {
        if (page.shops.isEmpty) {
          return NothingFoundHere(
            page: page,
            title: 'No shop delivers here yet',
            body: 'Every shop sets how far it will deliver. None of them '
                'reaches this address at the moment.',
            also: OutlinedButton(
              onPressed: hapticize(() => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const AddressListScreen()),
                  )),
              child: const Text('Choose another address'),
            ),
          );
        }
        return RefreshIndicator(
          onRefresh: () async => ref.invalidate(discoveryProvider(pin)),
          child: ListView.separated(
            padding: const EdgeInsets.all(16),
            // One extra row at the top for the widened notice, and one at the
            // bottom for "search farther" - both drawn only when the server
            // said there is something to draw.
            itemCount: page.shops.length + 2,
            separatorBuilder: (_, __) => const SizedBox(height: 12),
            itemBuilder: (context, index) {
              if (index == 0) return WidenedNotice(page: page);
              if (index == page.shops.length + 1) {
                return SearchFarther(page: page);
              }
              final shop = page.shops[index - 1];
              return MarketShopCard(
                shop: shop,
                nearest: index == 1,
                selected: shop.shopId == current,
              );
            },
          ),
        );
      },
    );
  }
}
