import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/images/gp_network_image.dart';
import '../../../core/marketplace/marketplace_models.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../../shared/widgets/scroll_to_top.dart';
import '../../marketplace/presentation/category_shops_screen.dart';
import '../../products/domain/product_models.dart';
import '../../products/presentation/category_products_screen.dart';
import '../../products/presentation/products_providers.dart';

/// Everything GP-STORE sells, in one place.
///
/// THE WHOLE CATALOGUE, DELIBERATELY. The home screen shows a handful of
/// categories because a home screen made mostly of category tiles is a menu,
/// not a shop front. This is where the rest live, and it has no cap: the list
/// is whatever the platform has defined, so a category added by a Super Admin
/// next year appears here the moment it is saved, with no release.
///
/// TWO LISTS, ONE SCREEN, AND THE DIFFERENCE MATTERS. On a marketplace the
/// categories somebody near this customer actually stocks come first, because
/// those are the ones that lead somewhere; the rest of the catalogue follows
/// under its own heading rather than being hidden, so a customer can see that
/// GP-STORE sells electronics even in a town where nobody nearby does yet.
/// Under a single shop there is only one list, which is what this screen has
/// always been.
class CategoriesScreen extends ConsumerWidget {
  const CategoriesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categoriesAsync = ref.watch(categoriesProvider);
    final marketplace = ref.watch(isMarketplaceProvider);
    // Only asked on a marketplace: under one shop there is nobody else to be
    // near, and the question would be a request that could never change the
    // screen.
    final nearby = marketplace
        ? ref.watch(marketCategoriesProvider).valueOrNull
        : null;

    return Scaffold(
      appBar: AppBar(
        title: const Text('All categories',
            style: TextStyle(fontWeight: FontWeight.w800)),
      ),
      body: categoriesAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text("Couldn't load categories"),
              const SizedBox(height: 8),
              TextButton(
                onPressed: hapticize(() => ref.invalidate(categoriesProvider)),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
        data: (categories) {
          if (categories.isEmpty) {
            return const Center(child: Text('No categories yet'));
          }
          final soldNearby = {for (final c in nearby ?? const <MarketCategory>[]) c.categoryId};
          final near = categories.where((c) => soldNearby.contains(c.id)).toList();
          final rest = categories.where((c) => !soldNearby.contains(c.id)).toList();

          return RefreshIndicator(
            onRefresh: () async {
              ref.invalidate(categoriesProvider);
              if (marketplace) ref.invalidate(marketCategoriesProvider);
            },
            child: ScrollToTop(
              builder: (context, scrollController) => CustomScrollView(
                controller: scrollController,
                slivers: [
                  if (near.isNotEmpty) ...[
                    const _Heading('Available near you'),
                    _CategoryGrid(categories: near, marketplace: marketplace),
                    const _Heading('Everything else on GP-STORE'),
                  ],
                  _CategoryGrid(categories: rest, marketplace: marketplace),
                  const SliverToBoxAdapter(child: SizedBox(height: 24)),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

class _Heading extends StatelessWidget {
  const _Heading(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => SliverToBoxAdapter(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
          child: Text(
            text,
            style: const TextStyle(
                fontWeight: FontWeight.w700,
                fontSize: 13,
                color: AppColors.textSecondary),
          ),
        ),
      );
}

/// A lazily-built grid, because a national catalogue is not a Column.
class _CategoryGrid extends StatelessWidget {
  const _CategoryGrid({required this.categories, required this.marketplace});

  final List<Category> categories;
  final bool marketplace;

  @override
  Widget build(BuildContext context) {
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      sliver: SliverGrid(
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 4,
          mainAxisSpacing: 4,
          crossAxisSpacing: 4,
          childAspectRatio: 0.78,
        ),
        delegate: SliverChildBuilderDelegate(
          (context, index) => CategoryTile(
            category: categories[index],
            marketplace: marketplace,
          ),
          childCount: categories.length,
        ),
      ),
    );
  }
}

/// One category: a round image and a name under it.
///
/// FOUR ACROSS RATHER THAN THREE, and small. A category tile is a signpost,
/// not a product - it carries no price, no rating and nothing to decide
/// between - so it should cost as little of the screen as it can while
/// staying tappable. Three big tiles per row was most of a phone screen for
/// nine words.
class CategoryTile extends StatelessWidget {
  const CategoryTile({
    super.key,
    required this.category,
    required this.marketplace,
  });

  final Category category;

  /// WHERE A CATEGORY LEADS DEPENDS ON WHETHER THERE IS A CHOICE OF SHOP.
  ///
  /// On a marketplace it opens the shops that sell it, because "which chemist
  /// is open" is the question a customer has before "which paracetamol". Under
  /// one shop there is no such question, and it opens the products exactly as
  /// it always has - §14: an existing customer must not have to learn that a
  /// multi-shop architecture exists.
  final bool marketplace;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: hapticize(() => Navigator.of(context).push(
            MaterialPageRoute(
              builder: (_) => marketplace
                  ? CategoryShopsScreen(
                      categoryId: category.id, categoryName: category.name)
                  : CategoryProductsScreen(category: category),
            ),
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
                color: AppColors.surfaceSoft,
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.divider),
              ),
              child: ClipOval(
                child: GpNetworkImage(
                  url: category.imageUrl,
                  renderWidth: 56,
                  fit: BoxFit.cover,
                  fallbackIcon: Icons.category_outlined,
                ),
              ),
            ),
            const SizedBox(height: 6),
            Text(
              category.name,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 11.5, height: 1.15, fontWeight: FontWeight.w600),
            ),
          ],
        ),
      ),
    );
  }
}
