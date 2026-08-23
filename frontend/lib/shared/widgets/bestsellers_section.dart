import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/bestseller_models.dart';
import '../../features/products/domain/product_models.dart';
import '../../features/products/presentation/category_products_screen.dart';
import '../../features/products/presentation/products_providers.dart';
import '../../core/images/gp_network_image.dart';
import '../../core/util/haptic_widgets.dart';

/// "Bestsellers" preview grid: a 2x2 photo collage per category, tapping a
/// tile opens the full category listing.
///
/// ONE REQUEST, NOT SIX. Each tile used to watch its own per-category
/// provider, so a cold home open issued six HTTP calls to draw twenty-four
/// thumbnails - six round trips, six auth filter chains, six connection
/// acquisitions. The whole collage now arrives from a single endpoint that
/// assembles it in one SQL statement and returns only the fields these tiles
/// draw: a category name and four image URLs.
///
/// [categories] is still accepted because tapping a tile navigates to
/// CategoryProductsScreen, which needs a real Category. The collage's
/// CONTENT no longer comes from it - the backend decides which categories
/// have enough to show - so a tile whose category is not in the list is
/// skipped rather than guessed at.
class BestsellersSection extends ConsumerWidget {
  const BestsellersSection({super.key, required this.categories});

  final List<Category> categories;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tiles = ref.watch(bestsellerTilesProvider).valueOrNull ?? const <BestsellerTile>[];
    if (tiles.isEmpty) return const SizedBox.shrink();

    // Only tiles we can actually navigate from. Rendering a tile whose
    // category the client does not know about would give the customer a
    // square that does nothing when tapped.
    final navigable = <({BestsellerTile tile, Category category})>[];
    for (final tile in tiles) {
      for (final category in categories) {
        if (category.id == tile.categoryId) {
          navigable.add((tile: tile, category: category));
          break;
        }
      }
    }
    if (navigable.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
          child: Text('Bestsellers', style: Theme.of(context).textTheme.titleLarge),
        ),
        GridView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          padding: const EdgeInsets.symmetric(horizontal: 16),
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 3,
            mainAxisSpacing: 12,
            crossAxisSpacing: 12,
            // Slightly taller than it was, to make room for the "+N more"
            // line under the name. The collage sits in an Expanded, so it
            // gives up the height rather than overflowing - but a tile that
            // tight leaves nothing for a customer running large text.
            childAspectRatio: 0.72,
          ),
          itemCount: navigable.length,
          itemBuilder: (context, index) => _BestsellerTile(
            tile: navigable[index].tile,
            category: navigable[index].category,
          ),
        ),
      ],
    );
  }
}

class _BestsellerTile extends StatelessWidget {
  const _BestsellerTile({required this.tile, required this.category});

  final BestsellerTile tile;
  final Category category;

  @override
  Widget build(BuildContext context) {
    // Stateless now: the images arrived with the collage, so there is
    // nothing left for this tile to fetch or watch.
    final imageUrls = tile.imageUrls;
    final extra = tile.additionalProductCount;

    return GestureDetector(
      onTap: hapticize(() => Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => CategoryProductsScreen(category: category)),
      )),
      child: Container(
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: AppColors.cardBackground,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          children: [
            Expanded(
              // The Builder that used to wrap this went with the per-tile
              // fetch it existed for - the images now arrive with the
              // collage, so there is nothing here that needs its own context.
              child: GridView.count(
                crossAxisCount: 2,
                mainAxisSpacing: 2,
                crossAxisSpacing: 2,
                physics: const NeverScrollableScrollPhysics(),
                children: List.generate(4, (i) {
                  final imageUrl = i < imageUrls.length ? imageUrls[i] : null;
                  // contain, not cover: cover crops to fill the square,
                  // which was cutting the tops off packet shots in a
                  // collage this small.
                  return GpNetworkImage.fill(
                    url: imageUrl,
                    borderRadius: BorderRadius.circular(6),
                    fallbackIcon: Icons.image_outlined,
                    fallbackIconSize: 16,
                  );
                }),
              ),
            ),
            const SizedBox(height: 6),
            Text(
              category.name,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
            ),
            // Only when there is genuinely more behind the four thumbnails.
            // A real count from the database, not products.length - the tile
            // always draws four, so counting what it drew would tell every
            // customer the same thing about a shelf of six and a shelf of
            // six hundred.
            if (extra != null)
              Text(
                '+$extra more',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 10,
                  color: AppColors.textSecondary,
                  fontWeight: FontWeight.w600,
                ),
              ),
          ],
        ),
      ),
    );
  }
}
