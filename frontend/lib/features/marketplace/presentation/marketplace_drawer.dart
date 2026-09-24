import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../orders/presentation/order_history_screen.dart';
import '../../support/presentation/contact_us_screen.dart';
import '../../wishlist/presentation/wishlist_screen.dart';
import '../domain/marketplace_feed_models.dart';
import 'marketplace_feed_provider.dart';

/// The customer's way into the three things GP-STORE can do for them.
///
/// <h3>Why a drawer and not three tabs on the home screen</h3>
///
/// Buy Online is what most customers want most of the time, and the home
/// screen should keep looking like a shop rather than like a menu of modes.
/// Visit to Buy and Service at Shop matter enormously when you want them and
/// are clutter when you do not, which is exactly what a drawer is for.
///
/// <h3>Nothing here is a dead end</h3>
///
/// Every destination is a screen that already exists and does something. A
/// menu entry that opens an empty page or a "coming soon" is worse than no
/// entry: it spends the customer's trust and teaches them not to look here
/// again. That is why there is no My Bookings - appointments are not built,
/// so the app does not pretend they are.
class MarketplaceDrawer extends ConsumerWidget {
  const MarketplaceDrawer({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final active = ref.watch(marketplaceModeFilterProvider);

    void chooseMode(CommerceMode mode) {
      ref.read(marketplaceModeFilterProvider.notifier).state = mode;
      Navigator.of(context).pop();
    }

    void chooseAll() {
      ref.read(marketplaceModeFilterProvider.notifier).state = null;
      Navigator.of(context).pop();
    }

    void open(Widget screen) {
      Navigator.of(context).pop();
      Navigator.of(context)
          .push(MaterialPageRoute(builder: (_) => screen));
    }

    return Drawer(
      backgroundColor: AppColors.cardBackground,
      child: SafeArea(
        child: ListView(
          padding: EdgeInsets.zero,
          children: [
            const _DrawerHeader(),
            const _SectionLabel('Shop'),
            _ModeTile(
              icon: Icons.store_mall_directory_outlined,
              title: 'All nearby shops',
              subtitle: 'Products and services across the marketplace',
              selected: active == null,
              onTap: chooseAll,
            ),
            _ModeTile(
              icon: Icons.shopping_bag_outlined,
              title: 'Buy Online',
              subtitle: 'Delivered or collected',
              selected: active == CommerceMode.buyOnline,
              onTap: () => chooseMode(CommerceMode.buyOnline),
            ),
            _ModeTile(
              icon: Icons.storefront_rounded,
              title: 'Visit to Buy',
              subtitle: 'See it first, buy at the shop',
              selected: active == CommerceMode.visitToBuy,
              onTap: () => chooseMode(CommerceMode.visitToBuy),
            ),
            _ModeTile(
              icon: Icons.build_circle_outlined,
              title: 'Service at Shop',
              subtitle: 'Find local services',
              selected: active == CommerceMode.serviceAtShop,
              onTap: () => chooseMode(CommerceMode.serviceAtShop),
            ),
            const Divider(height: 24, color: AppColors.divider),
            const _SectionLabel('You'),
            _PlainTile(
              icon: Icons.receipt_long_outlined,
              title: 'My Orders',
              onTap: () => open(const OrderHistoryScreen()),
            ),
            _PlainTile(
              icon: Icons.favorite_border_rounded,
              title: 'My Favourites',
              onTap: () => open(const WishlistScreen()),
            ),
            _PlainTile(
              icon: Icons.support_agent_outlined,
              title: 'Help & Support',
              onTap: () => open(const ContactUsScreen()),
            ),
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }
}

class _DrawerHeader extends StatelessWidget {
  const _DrawerHeader();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 28, 20, 22),
      decoration: const BoxDecoration(color: AppColors.primary),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text(
            'GP-STORE',
            style: TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w800,
              color: Colors.white,
              letterSpacing: 0.4,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            'Everything your town sells',
            style: TextStyle(
              fontSize: 12.5,
              color: Colors.white.withValues(alpha: 0.88),
            ),
          ),
        ],
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 6),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
          fontSize: 10.5,
          fontWeight: FontWeight.w700,
          letterSpacing: 1.1,
          color: AppColors.textSecondary,
        ),
      ),
    );
  }
}

class _ModeTile extends StatelessWidget {
  const _ModeTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      onTap: hapticize(onTap),
      selected: selected,
      selectedTileColor: AppColors.primary.withValues(alpha: 0.07),
      leading: Icon(icon,
          color: selected ? AppColors.primary : AppColors.textSecondary),
      title: Text(
        title,
        style: TextStyle(
          fontSize: 14.5,
          fontWeight: selected ? FontWeight.w700 : FontWeight.w600,
          color: selected ? AppColors.primary : AppColors.textPrimary,
        ),
      ),
      subtitle: Text(
        subtitle,
        style: const TextStyle(fontSize: 11.5, color: AppColors.textSecondary),
      ),
      trailing: selected
          ? const Icon(Icons.check_rounded, size: 18, color: AppColors.primary)
          : null,
    );
  }
}

class _PlainTile extends StatelessWidget {
  const _PlainTile({
    required this.icon,
    required this.title,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      onTap: hapticize(onTap),
      leading: Icon(icon, color: AppColors.textSecondary),
      title: Text(
        title,
        style: const TextStyle(
          fontSize: 14.5,
          fontWeight: FontWeight.w600,
          color: AppColors.textPrimary,
        ),
      ),
    );
  }
}
