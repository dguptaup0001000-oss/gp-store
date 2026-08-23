import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import 'reviews_providers.dart';
import 'write_review_dialog.dart';
import '../../../core/util/haptic_widgets.dart';

class ProductReviewsSection extends ConsumerWidget {
  const ProductReviewsSection({super.key, required this.productId});

  final int productId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reviewsAsync = ref.watch(productReviewsProvider(productId));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('Reviews', style: Theme.of(context).textTheme.titleMedium),
            TextButton(
              onPressed: hapticize(() async {
                final submitted = await showDialog<bool>(
                  context: context,
                  builder: (context) => WriteReviewDialog(productId: productId),
                );
                if (submitted == true) {
                  ref.invalidate(productReviewsProvider(productId));
                }
              }),
              child: const Text('Write a review'),
            ),
          ],
        ),
        reviewsAsync.when(
          loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
          error: (error, stackTrace) => const Text(
            "Couldn't load reviews",
            style: TextStyle(color: AppColors.textSecondary),
          ),
          data: (reviews) {
            if (reviews.isEmpty) {
              return const Padding(
                padding: EdgeInsets.symmetric(vertical: 8),
                child: Text('No reviews yet - be the first!', style: TextStyle(color: AppColors.textSecondary)),
              );
            }

            final averageRating = reviews.map((r) => r.rating).reduce((a, b) => a + b) / reviews.length;

            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.star, color: AppColors.gold, size: 18),
                    const SizedBox(width: 4),
                    Text('${averageRating.toStringAsFixed(1)} (${reviews.length} review${reviews.length == 1 ? '' : 's'})'),
                  ],
                ),
                const SizedBox(height: 8),
                ...reviews.map((review) => Container(
                      margin: const EdgeInsets.only(bottom: 8),
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(10)),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: List.generate(
                              5,
                              (index) => Icon(
                                index < review.rating ? Icons.star : Icons.star_border,
                                size: 14,
                                color: AppColors.primary,
                              ),
                            ),
                          ),
                          if (review.comment != null) ...[
                            const SizedBox(height: 4),
                            Text(review.comment!, style: Theme.of(context).textTheme.bodyMedium),
                          ],
                        ],
                      ),
                    )),
              ],
            );
          },
        ),
      ],
    );
  }
}
