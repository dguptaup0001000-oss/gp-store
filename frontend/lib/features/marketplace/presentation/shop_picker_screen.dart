import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/marketplace/marketplace_models.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/marketplace/shop_context.dart';
import '../../../core/theme/app_theme.dart';
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
    final shopsAsync = ref.watch(shopsNearProvider(pin));
    final current = ref.watch(shopContextProvider);

    return shopsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, _) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text("Couldn't load shops: ${extractErrorMessage(error)}"),
            const SizedBox(height: 8),
            TextButton(
              onPressed: hapticize(() => ref.invalidate(shopsNearProvider(pin))),
              child: const Text('Retry'),
            ),
          ],
        ),
      ),
      data: (shops) {
        if (shops.isEmpty) return const _NobodyDeliversHere();
        return RefreshIndicator(
          onRefresh: () async => ref.invalidate(shopsNearProvider(pin)),
          child: ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: shops.length,
            separatorBuilder: (_, __) => const SizedBox(height: 12),
            itemBuilder: (context, index) => _ShopTile(
              shop: shops[index],
              nearest: index == 0,
              selected: shops[index].shopId == current,
            ),
          ),
        );
      },
    );
  }
}

/// Nobody delivers to this address.
///
/// AND THERE IS NO "SEARCH FARTHER" BUTTON, deliberately. A shop appears here
/// only when the address is inside THAT SHOP'S OWN declared delivery radius,
/// which is the shop's decision and not the platform's. Widening the search
/// would list shops that would refuse the order at checkout - an offer the
/// app is not in a position to make. The two things that genuinely change the
/// answer are a different address or a new shop opening nearby, so those are
/// what this offers.
class _NobodyDeliversHere extends StatelessWidget {
  const _NobodyDeliversHere();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.storefront_outlined,
                size: 44, color: AppColors.textSecondary),
            const SizedBox(height: 12),
            const Text('No shop delivers here yet',
                style: TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            const Text(
              'Every shop sets how far it will deliver. None of them reaches '
              'this address at the moment. Try a different saved address.',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
            ),
            const SizedBox(height: 16),
            OutlinedButton(
              onPressed: hapticize(() => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const AddressListScreen()),
                  )),
              child: const Text('Choose another address'),
            ),
          ],
        ),
      ),
    );
  }
}

class _ShopTile extends ConsumerWidget {
  const _ShopTile({
    required this.shop,
    required this.nearest,
    required this.selected,
  });

  final Storefront shop;
  final bool nearest;
  final bool selected;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: hapticize(() {
        // SELECT, THEN LEAVE. The switch also discards the previous shop's
        // cached catalogue - see ShopSwitch - so the home screen behind this
        // one is rebuilding against the new shop by the time it is visible.
        ref.read(shopSwitchProvider).select(shop.shopId);
        Navigator.of(context).pop();
      }),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.cardBackground,
          borderRadius: BorderRadius.circular(12),
          border: selected
              ? Border.all(color: AppColors.primary, width: 1.5)
              : null,
        ),
        child: Row(
          children: [
            Icon(Icons.storefront_outlined,
                color: selected ? AppColors.primary : AppColors.textSecondary),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    shop.displayName ?? 'Shop ${shop.shopId}',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _distanceAndRange(shop),
                    style: theme.textTheme.bodySmall
                        ?.copyWith(color: AppColors.textSecondary),
                  ),
                  if (nearest && !selected) ...[
                    const SizedBox(height: 4),
                    const Text(
                      'Closest to your address',
                      style: TextStyle(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w600,
                        color: AppColors.secondary,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (selected)
              const Icon(Icons.check_circle, color: AppColors.primary, size: 20),
          ],
        ),
      ),
    );
  }

  /// How far away it is, and how far it is willing to come.
  ///
  /// Both numbers are the server's. distanceKm is null when the question had
  /// no pin in it, which is not a case this screen can reach - it always asks
  /// with an address - but it is rendered as absent rather than as zero.
  static String _distanceAndRange(Storefront shop) {
    final parts = <String>[];
    if (shop.distanceKm != null) {
      parts.add('${shop.distanceKm!.toStringAsFixed(1)} km away');
    }
    if (shop.maxDeliveryRadiusKm != null) {
      parts.add('delivers up to ${shop.maxDeliveryRadiusKm!.toStringAsFixed(0)} km');
    }
    return parts.isEmpty ? 'Delivery details unavailable' : parts.join(' · ');
  }
}
