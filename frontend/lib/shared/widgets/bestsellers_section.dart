import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/product_models.dart';
import '../../features/products/presentation/category_products_screen.dart';
import '../../features/products/presentation/products_providers.dart';

/// "Bestsellers" preview grid: for each of the first 6 categories, shows a
/// 2x2 photo collage of a few in-stock products plus the category name.
/// Tapping a tile opens the full category listing (CategoryProductsScreen).
/// This is a teaser fetching only 4 products per category, not a full
/// paginated browse, so no "total item count" is shown -- the Category
/// model doesn't carry a count and adding one is a separate backend task.
class BestsellersSection extends StatelessWidget {
  const BestsellersSection({super.key, required this.categories});

  final List<Category> categories;

  @override
  Widget build(BuildContext context) {
    if (categories.isEmpty) return const SizedBox.shrink();
    final featured = categories.take(6).toList();

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
          itemCount: featured.length,
          itemBuilder: (context, index) => _BestsellerTile(category: featured[index]),
        ),
      ],
    );
  }
}

class _BestsellerTile extends ConsumerWidget {
  const _BestsellerTile({required this.category});

  final Category category;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
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
              child: FutureBuilder<List<Product>>(
                future: ref.read(productsRepositoryProvider).browseByCategory(category.id, size: 4),
                builder: (context, snapshot) {
                  final products = snapshot.data ?? const [];
                  return GridView.count(
                    crossAxisCount: 2,
                    mainAxisSpacing: 2,
                    crossAxisSpacing: 2,
                    physics: const NeverScrollableScrollPhysics(),
                    children: List.generate(4, (i) {
                      final imageUrl = i < products.length ? products[i].primaryVariant?.imageUrl : null;
                      return ClipRRect(
                        borderRadius: BorderRadius.circular(6),
                        child: Container(
                          color: Colors.grey.shade200,
                          child: imageUrl != null
                              ? Image.network(
                                  imageUrl,
                                  fit: BoxFit.cover,
                                  errorBuilder: (c, e, s) => const Icon(
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
