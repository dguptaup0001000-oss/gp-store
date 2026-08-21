import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/images/product_image_url.dart';

import '../../../core/theme/app_theme.dart';
import '../../products/domain/product_models.dart';
import '../../products/presentation/category_products_screen.dart';
import '../../products/presentation/products_providers.dart';
import '../../../shared/widgets/scroll_to_top.dart';

/// Full grid of all store categories, reachable from the bottom nav bar.
/// Complements the compact CategoriesRow shown on the home screen —
/// this is the "see everything" destination rather than a teaser.
class CategoriesScreen extends ConsumerWidget {
  const CategoriesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categoriesAsync = ref.watch(categoriesProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Categories', style: TextStyle(fontWeight: FontWeight.w800)),
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
                onPressed: () => ref.invalidate(categoriesProvider),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
        data: (categories) {
          if (categories.isEmpty) {
            return const Center(child: Text('No categories yet'));
          }
          return ScrollToTop(
            builder: (context, scrollController) => GridView.builder(
            controller: scrollController,
            padding: const EdgeInsets.all(16),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 3,
              mainAxisSpacing: 16,
              crossAxisSpacing: 12,
              childAspectRatio: 0.8,
            ),
            itemCount: categories.length,
            itemBuilder: (context, index) {
              final category = categories[index];
              return _CategoryTile(category: category);
            },
            ),
          );
        },
      ),
    );
  }
}

class _CategoryTile extends StatelessWidget {
  const _CategoryTile({required this.category});

  final Category category;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () {
        HapticFeedback.selectionClick();
        Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => CategoryProductsScreen(category: category)),
        );
      },
      child: Column(
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: const BoxDecoration(
              color: AppColors.cardBackground,
              shape: BoxShape.circle,
            ),
            child: category.imageUrl != null
                ? ClipOval(
                    child: CachedNetworkImage(
                      imageUrl: ProductImageUrl.tile(category.imageUrl),
                      fit: BoxFit.cover,
                      // 72x72 tile - avoid decoding the full original.
                      memCacheWidth: 200,
                      errorWidget: (context, url, error) =>
                          const Icon(Icons.category_outlined, color: AppColors.textSecondary),
                    ),
                  )
                : const Icon(Icons.category_outlined, color: AppColors.textSecondary),
          ),
          const SizedBox(height: 8),
          Text(
            category.name,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12),
          ),
        ],
      ),
    );
  }
}
