import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/product_models.dart';
import '../../features/products/presentation/category_products_screen.dart';
import '../../core/util/haptic_widgets.dart';

/// Horizontal quick-jump tabs shown just below the search bar.
/// "All" always represents the current Home screen (it's not tappable —
/// you're already here); every other tab is a real store Category and
/// pushes straight to CategoryProductsScreen, reusing the existing
/// category browsing flow instead of re-filtering the home screen in place.
class CategoryTabsBar extends StatelessWidget {
  const CategoryTabsBar({super.key, required this.categories});

  final List<Category> categories;

  @override
  Widget build(BuildContext context) {
    if (categories.isEmpty) return const SizedBox.shrink();

    return SizedBox(
      height: 40,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: categories.length + 1,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          if (index == 0) {
            return const _TabChip(label: 'All', selected: true, onTap: null);
          }
          final category = categories[index - 1];
          return _TabChip(
            label: category.name,
            selected: false,
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => CategoryProductsScreen(category: category)),
            )),
          );
        },
      ),
    );
  }
}

class _TabChip extends StatelessWidget {
  const _TabChip({required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: selected ? AppColors.primary : AppColors.cardBackground,
          borderRadius: BorderRadius.circular(20),
        ),
        alignment: Alignment.center,
        child: Text(
          label,
          style: TextStyle(
            color: selected ? Colors.white : AppColors.textSecondary,
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
            fontSize: 13,
          ),
        ),
      ),
    );
  }
}
