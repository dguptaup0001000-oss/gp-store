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
import 'shop_profile_screen.dart';

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
          return _NothingHere(page: page, pin: pin);
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
              if (index == 0) return _WidenedNotice(page: page);
              if (index == page.shops.length + 1) {
                return _SearchFarther(page: page);
              }
              final shop = page.shops[index - 1];
              return _ShopTile(
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

/// "No shops within 8 km. Showing results within 20 km."
///
/// THE SENTENCE IS THE SERVER'S. The widening decision and the words
/// describing it are made in the same place, so a change to the ladder
/// cannot leave the app telling customers something that is no longer true.
/// Nothing is drawn when the server did not widen.
class _WidenedNotice extends StatelessWidget {
  const _WidenedNotice({required this.page});

  final DiscoveryPage page;

  @override
  Widget build(BuildContext context) {
    final message = page.message;
    if (!page.widened || message == null || message.isEmpty) {
      return const SizedBox.shrink();
    }
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.secondary.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          const Icon(Icons.travel_explore_outlined,
              size: 18, color: AppColors.secondary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

/// The next rung of the server's ladder, offered as a button.
///
/// THE APP DOES NOT PICK THE NUMBER. `nextRadiusKm` is what the server says
/// comes after what it just searched; when it is null there is nowhere
/// farther to go and this draws nothing rather than offering a search that
/// would return the same list.
class _SearchFarther extends ConsumerWidget {
  const _SearchFarther({required this.page});

  final DiscoveryPage page;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final next = page.nextRadiusKm;
    final searchedFar = ref.watch(discoveryRadiusProvider) != null;

    return Padding(
      padding: const EdgeInsets.only(top: 4, bottom: 24),
      child: Column(
        children: [
          if (next != null)
            OutlinedButton.icon(
              onPressed: hapticize(
                  () => ref.read(discoveryRadiusProvider.notifier).searchFarther(next)),
              icon: const Icon(Icons.expand_more, size: 18),
              label: Text('Search within ${_km(next)} km'),
            ),
          if (searchedFar) ...[
            const SizedBox(height: 4),
            TextButton(
              onPressed: hapticize(
                  () => ref.read(discoveryRadiusProvider.notifier).backToLocal()),
              child: const Text('Only shops that deliver to me'),
            ),
          ],
        ],
      ),
    );
  }
}

String _km(double value) =>
    value == value.roundToDouble() ? value.toStringAsFixed(0) : value.toStringAsFixed(1);

/// Nothing came back at this rung.
///
/// LOCAL-FIRST IS NOT LOCAL-ONLY. A shop appears in the unwidened list only
/// when the address is inside THAT SHOP'S OWN declared delivery radius, which
/// is the shop's decision and not the platform's - so the first answer is
/// still "nobody delivers here". What changed is that this is no longer the
/// end of the conversation: the customer can look farther, see that shops
/// exist, and decide for themselves, and every one of those rows says plainly
/// that it will not deliver to this address.
class _NothingHere extends ConsumerWidget {
  const _NothingHere({required this.page, required this.pin});

  final DiscoveryPage page;
  final ({double lat, double lng}) pin;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final next = page.nextRadiusKm;
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
            Text(
              page.message ??
                  'Every shop sets how far it will deliver. None of them reaches '
                      'this address at the moment.',
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textSecondary, fontSize: 13),
            ),
            const SizedBox(height: 16),
            if (next != null)
              FilledButton.icon(
                onPressed: hapticize(() =>
                    ref.read(discoveryRadiusProvider.notifier).searchFarther(next)),
                icon: const Icon(Icons.travel_explore_outlined, size: 18),
                label: Text('Look within ${_km(next)} km'),
              ),
            const SizedBox(height: 8),
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
      // OPENS THE SHOP, DOES NOT SWITCH TO IT. Choosing where to buy from is
      // worth more than one line of text on a tile - the shop's rating, what
      // it has verified, when it is open and what it promises about returns
      // all live on its profile, and "Shop here" is a deliberate button
      // there. Switching from a tile made the decision for the customer.
      onTap: hapticize(() => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => ShopProfileScreen(shopId: shop.shopId)),
          )),
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
                  // WHAT GP-STORE HAS CHECKED, in the server's own words.
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
                  // A SHOP OUTSIDE ITS OWN RADIUS IS STILL SHOWN, and is
                  // told plainly that it cannot deliver here. Searching
                  // farther widens what the customer can SEE; it widens
                  // nothing any shop promised.
                  if (shop.deliversHere == false) ...[
                    const SizedBox(height: 4),
                    const Text(
                      "Doesn't deliver to this address",
                      style: TextStyle(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w600,
                        color: AppColors.error,
                      ),
                    ),
                  ],
                  if (_whenItIsShut(shop) != null) ...[
                    const SizedBox(height: 4),
                    Text(
                      _whenItIsShut(shop)!,
                      style: const TextStyle(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w600,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ],
                  if (nearest && !selected && shop.deliversHere != false) ...[
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

  /// Why this shop will not take an order right now, or null when it will.
  ///
  /// BROWSING IS NEVER CLOSED, so none of this hides the shop - it explains
  /// it. "Back at 4pm" and "closed today" are different facts and a customer
  /// deciding where to buy from needs the difference.
  static String? _whenItIsShut(Storefront shop) {
    if (shop.closedToday) {
      final reason = shop.closureReason;
      return (reason == null || reason.isEmpty) ? 'Closed today' : 'Closed today - $reason';
    }
    if (!shop.acceptingOrders) {
      final until = shop.pausedUntil;
      return until == null ? 'Not taking orders right now' : 'Paused until $until';
    }
    if (!shop.openNow) return 'Closed right now';
    return null;
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
