import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/widgets/brand_avatar.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/filtered_product_browser.dart';
import '../domain/brand_models.dart';
import 'products_providers.dart';

/// Thin wrapper around the shared FilteredProductBrowser - owns just the
/// Scaffold/AppBar and the brand-specific fetch call. See
/// shared/widgets/filtered_product_browser.dart for the actual browsing UI,
/// which is identical to category browsing on purpose.
class BrandProductsScreen extends ConsumerWidget {
  const BrandProductsScreen({super.key, required this.brand});

  final BrandSummary brand;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            BrandAvatar(brandName: brand.brand, size: 32),
            const SizedBox(width: 10),
            Expanded(child: Text(brand.brand, overflow: TextOverflow.ellipsis)),
          ],
        ),
      ),
      body: FilteredProductBrowser(
        searchHint: 'Search within ${brand.brand}',
        fetchPage: ({sort, inStockOnly = false, keyword, required page}) {
          return ref.read(productsRepositoryProvider).browseByBrand(
                brand: brand.brand,
                sort: sort,
                inStockOnly: inStockOnly,
                keyword: keyword,
                page: page,
              );
        },
      ),
      bottomNavigationBar: const CartSummaryBar(),
    );
  }
}
