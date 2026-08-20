import 'package:flutter/material.dart';

import '../domain/product_models.dart';
import 'category_browse_screen.dart';

/// Kept as the entry point every screen already navigates to, now delegating
/// to the rail-based browser.
///
/// Four places push this - the home category row, the category tabs bar,
/// bestsellers, and the categories screen. Retargeting each of them would be
/// four chances to miss one and leave a single path on the old flat grid;
/// delegating here means opening a category from ANYWHERE gets the sidebar,
/// with no import churn and nothing else to keep in step.
///
/// This is not a wrapper for its own sake - it is the seam that lets the
/// layout change without touching the callers.
class CategoryProductsScreen extends StatelessWidget {
  const CategoryProductsScreen({super.key, required this.category});

  final Category category;

  @override
  Widget build(BuildContext context) {
    return CategoryBrowseScreen(initialCategory: category);
  }
}
