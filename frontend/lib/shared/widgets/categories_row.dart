import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/product_models.dart';

class CategoriesRow extends StatelessWidget {
  const CategoriesRow({super.key, required this.categories, this.onCategoryTap});

  final List<Category> categories;
  final void Function(Category category)? onCategoryTap;

  @override
  Widget build(BuildContext context) {
    if (categories.isEmpty) return const SizedBox.shrink();

    return SizedBox(
      height: 96,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: categories.length,
        separatorBuilder: (_, __) => const SizedBox(width: 16),
        itemBuilder: (context, index) {
          final category = categories[index];
          return GestureDetector(
            onTap: onCategoryTap == null
                ? null
                : () {
                    HapticFeedback.selectionClick();
                    onCategoryTap!(category);
                  },
            child: SizedBox(
              width: 64,
              child: Column(
                children: [
                  Container(
                    width: 56,
                    height: 56,
                    decoration: const BoxDecoration(
                      color: AppColors.cardBackground,
                      shape: BoxShape.circle,
                    ),
                    child: category.imageUrl != null
                        ? ClipOval(
                            child: CachedNetworkImage(
                              imageUrl: category.imageUrl!,
                              fit: BoxFit.cover,
                              // 56x56 tile - avoid decoding the full original.
                              memCacheWidth: 160,
                              errorWidget: (context, url, error) =>
                                  const Icon(Icons.category_outlined, color: AppColors.textSecondary),
                            ),
                          )
                        : const Icon(Icons.category_outlined, color: AppColors.textSecondary),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    category.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}
