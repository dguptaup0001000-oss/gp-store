import '../../../core/api/api_client.dart';
import '../domain/review_models.dart';

class ReviewsRepository {
  ReviewsRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<Review>> getForProduct(int productId, {int page = 0, int size = 10}) async {
    final response = await apiClient.dio.get(
      '/api/reviews/product/$productId',
      queryParameters: {'page': page, 'size': size},
    );
    final content = response.data['content'] as List;
    return content.map((e) => Review.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<Review>> getMyReviews() async {
    final response = await apiClient.dio.get('/api/reviews/mine');
    return (response.data as List).map((e) => Review.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Verified-purchase-only - the backend checks this against the caller's
  /// own order history, not anything the client asserts. Posting again for
  /// a product you've already reviewed updates your existing review rather
  /// than creating a duplicate.
  Future<Review> submitReview({required int productId, required int rating, String? comment}) async {
    final response = await apiClient.dio.post('/api/reviews', data: {
      'productId': productId,
      'rating': rating,
      'comment': comment,
    });
    return Review.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> deleteReview(int reviewId) async {
    await apiClient.dio.delete('/api/reviews/$reviewId');
  }
}
