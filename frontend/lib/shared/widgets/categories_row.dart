import 'package:flutter/material.dart';

import '../../core/images/gp_network_image.dart';
import '../../core/theme/app_theme.dart';
import '../../core/util/app_haptics.dart';
import '../../features/products/domain/product_models.dart';

/// The shop's aisles, as a two-row shelf of pastel cards.
///
/// WHAT THIS REPLACED, and why it is the same widget rather than a new one.
/// This used to be a single row of 56px circles - legible, but visually the
/// quietest thing on a page whose whole job is to send people into a
/// category. The home screen already carries CategoryTabsBar (compact text
/// chips, marking where you are) and the Categories tab carries the full
/// grid; adding a THIRD category widget for the shelf treatment would have
/// left three near-identical lists to keep in step. So this one grew up
/// instead of being duplicated.
///
/// TWO ROWS, SCROLLED SIDEWAYS. A single row shows four aisles on a phone
/// and buries the rest; a wrapping grid pushes everything below it off the
/// screen. Two rows in one horizontal scroller shows eight at a glance and
/// still costs one fixed slice of vertical space, whatever the catalogue
/// grows to.
///
/// THE WASHES ARE POSITIONAL, not random and not per-category-id: a category
/// keeps the same colour every time the screen opens, so the shelf is
/// recognisable rather than reshuffling under the customer.
class CategoriesRow extends StatelessWidget {
  const CategoriesRow({super.key, required this.categories, this.onCategoryTap});

  final List<Category> categories;
  final ValueChanged<Category>? onCategoryTap;

  /// The same soft washes the category rail uses, so the two read as one
  /// palette rather than two designers.
  static const _washes = [
    AppColors.mist,
    AppColors.peach,
    AppColors.cream,
    AppColors.surfaceSoft,
  ];

  static const double _cardWidth = 104;
  static const double _cardHeight = 116;
  static const double _gap = 10;

  /// Published so a caller's loading state can reserve exactly this much and
  /// the page below does not jump when the categories arrive.
  static const double shelfHeight = _cardHeight * 2 + _gap;

  @override
  Widget build(BuildContext context) {
    if (categories.isEmpty) return const SizedBox.shrink();

    // Ceil, so an odd number of categories still gets its last card - with
    // floor, a shop with seven aisles would silently show six.
    final columns = (categories.length + 1) ~/ 2;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 10),
          child: Text('Shop by category', style: Theme.of(context).textTheme.titleLarge),
        ),
        SizedBox(
          height: shelfHeight,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            // One item per COLUMN, each holding up to two cards. Laying it
            // out column-wise is what lets a single ListView.builder stay
            // lazy across two rows - a Wrap or a nested grid would build
            // every category up front.
            itemCount: columns,
            separatorBuilder: (_, __) => const SizedBox(width: _gap),
            itemBuilder: (context, column) {
              final top = column * 2;
              final bottom = top + 1;

              return Column(
                children: [
                  _CategoryCard(
                    category: categories[top],
                    wash: _washes[top % _washes.length],
                    onTap: onCategoryTap,
                  ),
                  const SizedBox(height: _gap),
                  // The last column of an odd list has no second card. An
                  // empty box of the same size keeps the column widths equal
                  // rather than letting the final one shrink and pull the
                  // row out of alignment.
                  if (bottom < categories.length)
                    _CategoryCard(
                      category: categories[bottom],
                      wash: _washes[bottom % _washes.length],
                      onTap: onCategoryTap,
                    )
                  else
                    const SizedBox(width: _cardWidth, height: _cardHeight),
                ],
              );
            },
          ),
        ),
      ],
    );
  }
}

class _CategoryCard extends StatelessWidget {
  const _CategoryCard({required this.category, required this.wash, required this.onTap});

  final Category category;
  final Color wash;
  final ValueChanged<Category>? onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap == null
          ? null
          : () {
              AppHaptics.selection();
              onTap!(category);
            },
      child: Container(
        width: CategoriesRow._cardWidth,
        height: CategoriesRow._cardHeight,
        padding: const EdgeInsets.fromLTRB(8, 8, 8, 6),
        decoration: BoxDecoration(
          color: wash,
          borderRadius: BorderRadius.circular(AppRadius.lg),
        ),
        child: Column(
          children: [
            Expanded(
              // cover, not contain: category art is scene photography, and
              // filling the card is the point. Product shots are the
              // opposite case - see GpNetworkImage.
              child: GpNetworkImage.fill(
                url: category.imageUrl,
                fit: BoxFit.cover,
                borderRadius: BorderRadius.circular(AppRadius.md),
                fallbackIcon: Icons.category_outlined,
              ),
            ),
            const SizedBox(height: 6),
            // Fixed two-line slot rather than a Text that grows: without it
            // a one-word aisle and a three-word one produce cards of
            // different heights, and the second row stops lining up.
            SizedBox(
              height: 26,
              child: Text(
                category.name,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 11,
                  height: 1.15,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textPrimary,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
