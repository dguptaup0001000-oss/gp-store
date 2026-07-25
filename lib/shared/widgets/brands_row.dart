import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/brand_models.dart';
import 'brand_avatar.dart';

class BrandsRow extends StatelessWidget {
  const BrandsRow({super.key, required this.brands, this.onBrandTap});

  final List<BrandSummary> brands;
  final void Function(BrandSummary brand)? onBrandTap;

  @override
  Widget build(BuildContext context) {
    if (brands.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
          child: Text('Shop by Brand', style: Theme.of(context).textTheme.titleLarge),
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
