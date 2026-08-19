import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/reviews_repository.dart';
import '../domain/review_models.dart';

final reviewsRepositoryProvider = Provider<ReviewsRepository>((ref) {
  return ReviewsRepository(apiClient: ref.watch(apiClientProvider));
});

// autoDispose - a customer can browse many different products' reviews (and
// visit their own review history) in one session; keeping every one of
// those cached forever once no longer visible is unbounded growth for no
// benefit, unlike a screen you return to constantly.
final productReviewsProvider = FutureProvider.autoDispose.family<List<Review>, int>((ref, productId) {
  return ref.watch(reviewsRepositoryProvider).getForProduct(productId);
});

final myReviewsProvider = FutureProvider.autoDispose<List<Review>>((ref) {
  return ref.watch(reviewsRepositoryProvider).getMyReviews();
});
