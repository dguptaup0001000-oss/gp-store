import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminReviewsScreen extends ConsumerWidget {
  const AdminReviewsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reviewsAsync = ref.watch(adminAllReviewsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Review Moderation')),
      body: reviewsAsync.when(
        loading: () => const AdminListSkeleton(),
        error: (error, stackTrace) => AdminErrorState(
          // Shows the real failure reason rather than one static
          // string - an admin who can read the cause can act on it.
          message: "Couldn't load reviews: ${extractErrorMessage(error)}",
          onRetry: hapticize(() => ref.invalidate(adminAllReviewsProvider)),
        ),
        data: (page) {
          final reviews = page.reviews;
          if (reviews.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.rate_review_outlined,
              title: 'No reviews yet',
              message: 'Customer reviews appear here for moderation.',
            );
          }

          final hasMore = ref.read(adminAllReviewsProvider.notifier).hasMore;

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: reviews.length + (hasMore ? 1 : 0),
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, index) {
              if (index == reviews.length) {
                WidgetsBinding.instance.addPostFrameCallback(
                  (_) => ref.read(adminAllReviewsProvider.notifier).loadMore(),
                );
                return const Padding(
                  padding: EdgeInsets.all(AdminSpacing.lg),
                  child: Center(
                    child: SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ),
                );
              }

              final review = reviews[index];
              return Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: List.generate(
                              5,
                              (i) => Icon(
                                i < review.rating ? Icons.star : Icons.star_border,
                                size: 14,
                                color: AdminColors.primary,
                              ),
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(review.productName ?? 'Product', style: const TextStyle(fontWeight: FontWeight.w700)),
                          if (review.comment != null) ...[
                            const SizedBox(height: 4),
                            Text(review.comment!, style: Theme.of(context).textTheme.bodyMedium),
                          ],
                          const SizedBox(height: 6),
                          Text(
                            'By ${review.customerName ?? "unknown"}${review.customerEmail != null ? ' (${review.customerEmail})' : ''}',
                            style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.delete_outline, color: AdminColors.danger, size: 20),
                      tooltip: 'Remove review',
                      onPressed: hapticize(() async {
                        final confirmed = await showDialog<bool>(
                          context: context,
                          builder: (context) => AlertDialog(
                            title: const Text('Remove this review?'),
                            content: const Text('This cannot be undone.'),
                            actions: [
                              TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
                              TextButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Remove')),
                            ],
                          ),
                        );
                        if (confirmed != true) return;

                        try {
                          await ref.read(adminProductsRepositoryProvider).moderateDeleteReview(review.id);
                          ref.invalidate(adminAllReviewsProvider);
                        } catch (e) {
                          if (!context.mounted) return;
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(extractErrorMessage(e))),
                          );
                        }
                      }),
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
