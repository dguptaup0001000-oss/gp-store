import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/product_models.dart';

class OffersBanner extends StatelessWidget {
  const OffersBanner({super.key, required this.offers});

  final List<Coupon> offers;

  @override
  Widget build(BuildContext context) {
    if (offers.isEmpty) return const SizedBox.shrink();

    return SizedBox(
      height: 76,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: offers.length,
        separatorBuilder: (_, __) => const SizedBox(width: 12),
        itemBuilder: (context, index) {
          final offer = offers[index];
          final label = offer.discountType == DiscountType.percentage
              ? '${offer.discountValue.toStringAsFixed(0)}% OFF'
              : '₹${offer.discountValue.toStringAsFixed(0)} OFF';

          return Container(
            width: 220,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: BoxDecoration(
              color: AppColors.cardBackground,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                const Icon(Icons.local_offer_outlined, color: AppColors.primary),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(label, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
                      const SizedBox(height: 2),
                      Text(
                        'Use code ${offer.couponCode}',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}
