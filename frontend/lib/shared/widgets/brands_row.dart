import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/brand_models.dart';
import 'brand_avatar.dart';

class BrandsRow extends StatelessWidget {
  const BrandsRow({
    super.key,
    required this.brands,
    this.onBrandTap,
    this.onSeeAll,
  });

  final List<BrandSummary> brands;
  final void Function(BrandSummary brand)? onBrandTap;

  /// The full brand list, when the caller has somewhere to send them.
  ///
  /// THIS ROW SHOWS WHAT FITS, and a customer looking for a brand that did not
  /// fit needs a way to the rest. It used to live on a second brand banner
  /// below the fold; that banner is gone and the route it owned would have
  /// gone with it, which is how a screen becomes unreachable without anybody
  /// deleting it.
  final VoidCallback? onSeeAll;

  @override
  Widget build(BuildContext context) {
    if (brands.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsets.fromLTRB(16, 20, onSeeAll == null ? 16 : 8, 4),
          child: Row(
            children: [
              const Expanded(
                child: Text('Shop by brand',
                    style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
              ),
              if (onSeeAll != null)
                TextButton(onPressed: onSeeAll, child: const Text('See all')),
            ],
          ),
        ),
        SizedBox(
          height: 104,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            itemCount: brands.length,
            separatorBuilder: (_, __) => const SizedBox(width: 16),
            itemBuilder: (context, index) {
              final brand = brands[index];
              return GestureDetector(
                onTap: onBrandTap == null ? null : () => onBrandTap!(brand),
                child: SizedBox(
                  width: 72,
                  child: Column(
                    children: [
                      BrandAvatar(brandName: brand.brand),
                      const SizedBox(height: 6),
                      Text(
                        brand.brand,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
                      ),
                      Text(
                        '${brand.productCount} item${brand.productCount == 1 ? '' : 's'}',
                        style: const TextStyle(fontSize: 10, color: AppColors.textSecondary),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}
