import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/images/product_image_url.dart';
import '../../core/theme/app_theme.dart';
import '../../features/products/domain/bestseller_models.dart';
import '../../features/products/domain/product_models.dart';
import '../../features/products/presentation/category_products_screen.dart';
import '../../features/products/presentation/products_providers.dart';

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
            childAspectRatio: 0.78,
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

    return GestureDetector(
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => CategoryProductsScreen(category: category)),
      ),
      child: Container(
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: AppColors.cardBackground,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          children: [
            Expanded(
              child: Builder(
                builder: (context) {
                  return GridView.count(
                    crossAxisCount: 2,
                    mainAxisSpacing: 2,
                    crossAxisSpacing: 2,
                    physics: const NeverScrollableScrollPhysics(),
                    children: List.generate(4, (i) {
                      final imageUrl = i < imageUrls.length ? imageUrls[i] : null;
                      return ClipRRect(
                        borderRadius: BorderRadius.circular(6),
                        child: Container(
                          color: Colors.grey.shade200,
                          child: imageUrl != null
                              ? CachedNetworkImage(
                                  imageUrl: ProductImageUrl.tile(imageUrl),
                                  // contain (not cover) - matches every other
                                  // product image in the app. cover crops to
                                  // fill the tile, which was cutting off parts
                                  // of product photos in this 2x2 collage.
                                  fit: BoxFit.contain,
                                  // A 2x2 collage tile - tiny on screen.
                                  memCacheWidth: 200,
                                  errorWidget: (c, url, e) => const Icon(
                                    Icons.image_outlined,
                                    size: 16,
                                    color: AppColors.textSecondary,
                                  ),
                                )
                              : const Icon(Icons.image_outlined, size: 16, color: AppColors.textSecondary),
                        ),
                      );
                    }),
                  );
                },
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
          ],
        ),
      ),
    );
  }
}
