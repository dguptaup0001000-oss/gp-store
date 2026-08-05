import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/reviews_repository.dart';
import '../domain/review_models.dart';

final reviewsRepositoryProvider = Provider<ReviewsRepository>((ref) {
  return ReviewsRepository(apiClient: ref.watch(apiClientProvider));
});

final productReviewsProvider = FutureProvider.family<List<Review>, int>((ref, productId) {
  return ref.watch(reviewsRepositoryProvider).getForProduct(productId);
});

final myReviewsProvider = FutureProvider<List<Review>>((ref) {
  // See myProfileProvider's comment (profile_providers.dart) - without this
  // watch, a different account logging in on the same device without a full
  // app restart would keep seeing the PREVIOUS customer's own reviews.
  ref.watch(authControllerProvider);
  return ref.watch(reviewsRepositoryProvider).getMyReviews();
});
