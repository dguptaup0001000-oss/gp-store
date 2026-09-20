import '../../../core/marketplace/marketplace_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/util/app_haptics.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../address/presentation/address_list_screen.dart';
import '../../address/presentation/address_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../cart/presentation/cart_screen.dart';
import '../../marketplace/presentation/shop_picker_screen.dart';
import '../../notifications/presentation/notifications_screen.dart';
import '../../products/presentation/search_screen.dart';
import '../../profile/presentation/profile_screen.dart';
import 'home_load_stage.dart';

/// Who you are, where you are, and how to search - in one band.
///
/// WHERE, NOT WHICH SHOP, IS THE HEADLINE. The old title bar named the shop
/// the app was acting for, which is the right answer for a shop's app and the
/// wrong one for a marketplace: a customer opening GP-STORE is somewhere
/// before they are in any particular shop, and the thing they most often need
/// to change is the address, not the storefront. The shop is still one tap
/// away, and still named on every screen that is actually about one shop.
class HomeHeader extends ConsumerWidget {
  const HomeHeader({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      color: AppColors.cardBackground,
      padding: EdgeInsets.only(top: MediaQuery.of(context).padding.top),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 8, 6, 10),
        child: Column(
          children: [
            Row(
              children: [
                // THE WAY INTO THE OTHER TWO MODES. Only on a marketplace:
                // under one shop there is nothing to switch between, and a
                // menu button that opens a drawer of one option is furniture.
                if (ref.watch(isMarketplaceProvider))
                  IconButton(
                    visualDensity: VisualDensity.compact,
                    icon: const Icon(Icons.menu_rounded),
                    tooltip: 'Browse',
                    onPressed: hapticize(() => Scaffold.of(context).openDrawer()),
                  ),
                const _Mark(),
                const SizedBox(width: 10),
                const Expanded(child: _DeliveringTo()),
                const _NotificationsButton(),
                const _CartButton(),
                IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.person_outline),
                  tooltip: 'Profile',
                  onPressed: hapticize(() => Navigator.of(context).push(
                        MaterialPageRoute(builder: (_) => const ProfileScreen()),
                      )),
                ),
              ],
            ),
            const SizedBox(height: 10),
            const _SearchPill(),
          ],
        ),
      ),
    );
  }
}

/// The GP-Store mark. Small, and the only piece of branding on the screen.
class _Mark extends StatelessWidget {
  const _Mark();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 32,
      height: 32,
      decoration: const BoxDecoration(
        color: AppColors.primary,
        borderRadius: BorderRadius.all(Radius.circular(9)),
      ),
      child: const Center(
        child: Text('G',
            style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.w800,
                fontSize: 17,
                height: 1.1)),
      ),
    );
  }
}

/// The address this app is shopping for, and a way to change it.
///
/// THE SHOP SITS UNDER IT, not instead of it, and only when there is a choice
/// to make. Under a single shop this is just the address; on a marketplace the
/// second line names the storefront, so a customer who believes they are in
/// GP Store and is actually in Deepak Hardware finds out before they buy.
class _DeliveringTo extends ConsumerWidget {
  const _DeliveringTo();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final addresses = ref.watch(myAddressesProvider).valueOrNull;
    final chosen = (addresses == null || addresses.isEmpty)
        ? null
        : addresses.firstWhere((a) => a.defaultAddress,
            orElse: () => addresses.first);

    // AREA AND CITY, NOT THE WHOLE ADDRESS. "Civil Lines, Kanpur" is what a
    // customer needs to recognise where their order is going; the house
    // number is theirs and adding it makes the header a line of small print.
    // Both are required on the model, so there is no null case to invent a
    // fallback for - only the case where they have no address at all.
    final where = chosen == null
        ? 'Set your address'
        : '${chosen.area}, ${chosen.city}';

    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: hapticize(() => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const AddressListScreen()),
          )),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                const Icon(Icons.location_on_outlined,
                    size: 14, color: AppColors.primary),
                const SizedBox(width: 2),
                Flexible(
                  child: Text(
                    where,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontWeight: FontWeight.w700, fontSize: 13.5, height: 1.2),
                  ),
                ),
                const Icon(Icons.keyboard_arrow_down, size: 16),
              ],
            ),
            const _ShopLine(),
          ],
        ),
      ),
    );
  }
}

/// The storefront the app is acting for - on a marketplace only.
class _ShopLine extends ConsumerWidget {
  const _ShopLine();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // BEHIND THE SAME GATE AS THE REST OF THE BELOW-THE-FOLD WORK. Asking
    // whether this deployment is a marketplace is one more request on the
    // critical path, and under a single shop the answer never changes what is
    // drawn here.
    if (!ref.watch(homeBelowFoldReadyProvider)) return const SizedBox.shrink();
    if (!ref.watch(isMarketplaceProvider)) return const SizedBox.shrink();

    final storefront = ref.watch(selectedStorefrontProvider).valueOrNull;
    final label = storefront?.shop.displayName ?? 'Choose a shop';

    return GestureDetector(
      // Opaque so this tap opens the shop picker rather than falling through
      // to the address row it sits inside.
      behavior: HitTestBehavior.opaque,
      onTap: () {
        AppHaptics.selection();
        Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const ShopPickerScreen()),
        );
      },
      child: Padding(
        padding: const EdgeInsets.only(left: 16, top: 1),
        child: Text(
          'Shopping at $label',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
              fontSize: 11, color: AppColors.textSecondary, height: 1.2),
        ),
      ),
    );
  }
}

class _NotificationsButton extends StatelessWidget {
  const _NotificationsButton();

  @override
  Widget build(BuildContext context) {
    return IconButton(
      visualDensity: VisualDensity.compact,
      icon: const Icon(Icons.notifications_none_rounded),
      tooltip: 'Notifications',
      onPressed: hapticize(() => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const NotificationsScreen()),
          )),
    );
  }
}

/// The cart, with what is in it.
class _CartButton extends ConsumerWidget {
  const _CartButton();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final count = ref.watch(cartControllerProvider).valueOrNull?.totalItems ?? 0;
    return Stack(
      alignment: Alignment.center,
      children: [
        IconButton(
          visualDensity: VisualDensity.compact,
          icon: const Icon(Icons.shopping_cart_outlined),
          tooltip: 'Cart',
          onPressed: hapticize(() => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const CartScreen()),
              )),
        ),
        if (count > 0)
          Positioned(
            top: 4,
            right: 4,
            child: Container(
              padding: const EdgeInsets.all(3),
              decoration: const BoxDecoration(
                  color: AppColors.primary, shape: BoxShape.circle),
              constraints: const BoxConstraints(minWidth: 16, minHeight: 16),
              child: Text(
                '$count',
                textAlign: TextAlign.center,
                style: const TextStyle(
                    color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700),
              ),
            ),
          ),
      ],
    );
  }
}

/// One search box for products, shops, categories and brands.
///
/// SAYS WHAT IT SEARCHES. "Search for atta, dal, coke and more" was true of a
/// kirana and is an advertisement for groceries on a marketplace that also
/// sells saris and phones - a customer looking for a chemist would not think
/// to type it here.
class _SearchPill extends StatelessWidget {
  const _SearchPill();

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: hapticize(() => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const SearchScreen()),
          )),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
        decoration: BoxDecoration(
          color: AppColors.surfaceSoft,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.divider),
        ),
        child: Row(
          children: [
            const Icon(Icons.search, size: 20, color: AppColors.textSecondary),
            const SizedBox(width: 8),
            const Expanded(
              child: Text(
                'Search shops, products and brands',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: AppColors.textSecondary, fontSize: 13.5),
              ),
            ),
            // Opens search ALREADY LISTENING, so the microphone is one gesture
            // rather than two. The pill's own tap still opens a keyboard,
            // which is what somebody who wants to type expects.
            GestureDetector(
              onTap: () {
                AppHaptics.selection();
                Navigator.of(context).push(
                  MaterialPageRoute(
                      builder: (_) => const SearchScreen(openVoice: true)),
                );
              },
              behavior: HitTestBehavior.opaque,
              child: const Padding(
                padding: EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                child: Icon(Icons.mic_none_rounded,
                    size: 20, color: AppColors.primary),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
