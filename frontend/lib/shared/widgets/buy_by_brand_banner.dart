import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../features/brands/presentation/brands_screen.dart';
import '../../features/products/domain/brand_models.dart';
import 'brand_avatar.dart';

/// Compact "Buy by Brand" entry point for the home screen -- deliberately
/// NOT a full row of every brand (that's what BrandsScreen is for). Shows
/// a handful of sample avatars as a preview, and tapping anywhere opens
/// the complete searchable brand grid.
class BuyByBrandBanner extends StatelessWidget {
  const BuyByBrandBanner({super.key, required this.brands});

  final List<BrandSummary> brands;

  @override
  Widget build(BuildContext context) {
    if (brands.isEmpty) return const SizedBox.shrink();
    final preview = brands.take(4).toList();

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
      child: GestureDetector(
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const BrandsScreen()),
        ),
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: AppColors.cardBackground,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Row(
            children: [
              SizedBox(
                width: 72,
                height: 40,
                child: Stack(
                  children: [
                    for (var i = 0; i < preview.length; i++)
                      Positioned(
                        left: i * 18.0,
                        child: Container(
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            border: Border.all(color: AppColors.cardBackground, width: 2),
                          ),
                          child: BrandAvatar(brandName: preview[i].brand, size: 40),
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Buy by Brand', style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 2),
                    Text(
                      '${brands.length} brands to explore',
                      style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, color: AppColors.textSecondary),
            ],
          ),
        ),
      ),
    );
  }
}
