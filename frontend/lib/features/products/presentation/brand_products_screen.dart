import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/brand_avatar.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/filtered_product_browser.dart';
import '../domain/brand_models.dart';
import 'products_providers.dart';

/// Thin wrapper around the shared FilteredProductBrowser - owns just the
/// Scaffold/AppBar, the brand storefront band, and the brand-specific fetch
/// call. See shared/widgets/filtered_product_browser.dart for the actual
/// browsing UI, which is identical to category browsing on purpose.
class BrandProductsScreen extends ConsumerWidget {
  const BrandProductsScreen({super.key, required this.brand});

  final BrandSummary brand;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: Text(brand.brand, overflow: TextOverflow.ellipsis)),
      body: FilteredProductBrowser(
        searchHint: 'Search within ${brand.brand}',
        header: _BrandStorefrontBand(brand: brand),
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

/// The band that turns a filtered list into a storefront.
///
/// Warm ivory rather than the page's mint ground, because "premium sections
/// use cream/ivory" is the one place in the palette where a surface changes
/// colour to mean something - here it means you have entered a brand's own
/// space rather than a generic results page.
///
/// productCount comes from the BrandSummary the caller already has, so this
/// costs no extra request.
class _BrandStorefrontBand extends StatelessWidget {
  const _BrandStorefrontBand({required this.brand});

  final BrandSummary brand;

  @override
  Widget build(BuildContext context) {
    final count = brand.productCount;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 18),
      decoration: const BoxDecoration(
        color: AppColors.ivory,
        border: Border(bottom: BorderSide(color: AppColors.divider)),
      ),
      child: Row(
        children: [
          // Lifted off the ivory so the mark reads as an object on paper -
          // the same product-on-card depth used throughout the app, scaled to
          // a logo.
          Container(
            decoration: const BoxDecoration(shape: BoxShape.circle, boxShadow: AppElevation.tile),
            child: BrandAvatar(brandName: brand.brand, size: 52),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  brand.brand,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 19,
                    fontWeight: FontWeight.w800,
                    color: AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  // Singular/plural rather than "1 products", which is the
                  // kind of detail that makes an app feel unfinished.
                  count == 1 ? '1 product' : '$count products',
                  style: const TextStyle(fontSize: 13, color: AppColors.textSecondary),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
