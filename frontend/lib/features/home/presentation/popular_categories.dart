import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../categories/presentation/categories_screen.dart';
import '../../products/domain/product_models.dart';
import '../../products/presentation/products_providers.dart';

/// The handful of categories worth putting on a home screen, and a way to the
/// rest.
///
/// EIGHT CELLS, NOT A CATALOGUE. A home screen made mostly of category tiles
/// is a menu, and a customer who wanted a menu would have opened the
/// Categories tab. Seven categories and an "All" tile is two rows - enough to
/// say "this is not a grocery app" at a glance, small enough that the shops
/// and offers below it are still on the first screen.
///
/// WHICH SEVEN IS NOT A LIST IN THIS FILE, and that is the point. On a
/// marketplace they come from the server already ordered by how many nearby
/// shops stock each, so the customer in a town with three chemists and no
/// electronics shop is shown medicine rather than electronics - and a category
/// added by a Super Admin next year can reach this row on its first merchant's
/// first listing. Under a single shop it is the shop's own catalogue order.
/// Neither is hard-coded and neither is capped.
class PopularCategories extends ConsumerWidget {
  const PopularCategories({super.key, this.howMany = 7});

  /// Seven, plus the All tile, is two rows of four. Adjustable rather than
  /// constant so a future layout can ask for a different shape without this
  /// widget having to be rewritten - but never a maximum on what EXISTS, only
  /// on what this row draws.
  final int howMany;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final catalogue = ref.watch(categoriesProvider).valueOrNull;
    if (catalogue == null || catalogue.isEmpty) {
      // Reserves nothing and says nothing: the shelf below simply starts
      // higher until the categories land, rather than the page jumping.
      return const SizedBox.shrink();
    }

    final marketplace = ref.watch(isMarketplaceProvider);
    final ordered = marketplace
        ? _mostStockedNearby(ref, catalogue)
        : catalogue;
    final shown = ordered.take(howMany).toList();
    if (shown.isEmpty) return const SizedBox.shrink();

    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 4, 8, 4),
      child: GridView.count(
        // Inside a CustomScrollView's sliver list, so it must not scroll and
        // must size itself: eight fixed cells is a bounded, cheap build, which
        // is what makes shrinkWrap acceptable here and not in the feed below.
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        padding: EdgeInsets.zero,
        crossAxisCount: 4,
        mainAxisSpacing: 2,
        crossAxisSpacing: 2,
        childAspectRatio: 0.82,
        children: [
          for (final category in shown)
            CategoryTile(category: category, marketplace: marketplace),
          const _AllCategoriesTile(),
        ],
      ),
    );
  }

  /// The catalogue, reordered by what shops near this customer actually stock.
  ///
  /// FALLS BACK TO THE CATALOGUE'S OWN ORDER, never to an empty row. The
  /// nearby list is null while it loads, absent on a customer with no address
  /// yet, and empty for a pin nobody delivers to - and in all three the right
  /// answer is the catalogue rather than a blank space where the categories
  /// should be.
  ///
  /// CATEGORIES NOBODY NEARBY STOCKS ARE NOT DROPPED, only pushed behind the
  /// ones that are. Seven tiles of nothing-near-you is a worse home screen
  /// than seven that lead somewhere, and the whole catalogue is one tap away
  /// under All.
  static List<Category> _mostStockedNearby(WidgetRef ref, List<Category> catalogue) {
    final nearby = ref.watch(marketCategoriesProvider).valueOrNull;
    if (nearby == null || nearby.isEmpty) return catalogue;

    final rank = <int, int>{};
    for (var i = 0; i < nearby.length; i++) {
      rank[nearby[i].categoryId] = i;
    }
    // ORDERED, NOT FILTERED, and stable: two categories nobody stocks keep
    // their catalogue order rather than shuffling between two builds.
    final sorted = List<Category>.from(catalogue);
    sorted.sort((a, b) {
      final ra = rank[a.id] ?? 1 << 30;
      final rb = rank[b.id] ?? 1 << 30;
      return ra == rb ? a.id.compareTo(b.id) : ra.compareTo(rb);
    });
    return sorted;
  }
}

/// Compact first-row shortcuts, drawn from the same live category catalogue.
/// The full image grid below remains the browsable category surface.
class MainCategoryShortcuts extends ConsumerWidget {
  const MainCategoryShortcuts({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categories = ref.watch(categoriesProvider).valueOrNull;
    if (categories == null || categories.isEmpty) return const SizedBox.shrink();
    final marketplace = ref.watch(isMarketplaceProvider);
    return SizedBox(
      height: 106,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        children: [
          for (final category in categories.take(7))
            SizedBox(
              width: 78,
              child: CategoryTile(category: category, marketplace: marketplace),
            ),
          const SizedBox(width: 78, child: _AllCategoriesTile()),
        ],
      ),
    );
  }
}

/// The eighth cell: everything this row did not have room for.
///
/// A TILE RATHER THAN A "See all" LINK beside the heading, because it sits
/// where a customer's thumb already is after reading the other seven, and
/// because a row of eight with one of them labelled "All" reads as complete
/// in a way that seven-plus-a-link does not.
class _AllCategoriesTile extends StatelessWidget {
  const _AllCategoriesTile();

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: hapticize(() => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const CategoriesScreen()),
          )),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: AppColors.tint(AppColors.primary),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.grid_view_rounded,
                  color: AppColors.primary, size: 24),
            ),
            const SizedBox(height: 6),
            const Text(
              'All',
              style: TextStyle(
                  fontSize: 11.5,
                  height: 1.15,
                  fontWeight: FontWeight.w700,
                  color: AppColors.primary),
            ),
          ],
        ),
      ),
    );
  }
}
