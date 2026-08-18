import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import 'reviews_providers.dart';

class MyReviewsScreen extends ConsumerWidget {
  const MyReviewsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reviewsAsync = ref.watch(myReviewsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('My Reviews')),
      body: reviewsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load your reviews: ${extractErrorMessage(error)}"),
              TextButton(onPressed: () => ref.invalidate(myReviewsProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (reviews) {
          if (reviews.isEmpty) {
            return const Center(
              child: Text("You haven't written any reviews yet", style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: reviews.length,
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, index) {
              final review = reviews[index];
              return Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(review.product?.name ?? 'Product', style: const TextStyle(fontWeight: FontWeight.w700)),
                          const SizedBox(height: 4),
                          Row(
                            children: List.generate(
                              5,
                              (i) => Icon(i < review.rating ? Icons.star : Icons.star_border, size: 14, color: AppColors.primary),
                            ),
                          ),
                          if (review.comment != null) ...[
                            const SizedBox(height: 4),
                            Text(review.comment!, style: Theme.of(context).textTheme.bodyMedium),
                          ],
                        ],
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.delete_outline, color: AppColors.error, size: 20),
                      tooltip: 'Delete review',
                      onPressed: () async {
                        try {
                          await ref.read(reviewsRepositoryProvider).deleteReview(review.id);
                          ref.invalidate(myReviewsProvider);
                        } catch (e) {
                          if (!context.mounted) return;
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(extractErrorMessage(e))),
                          );
                        }
                      },
                    ),
                  ],
                ),
              );
            },
          );
        },
      ),
    );
  }
}
