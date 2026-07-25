import '../../../core/api/api_client.dart';
import '../domain/product_models.dart';

class ProductsRepository {
  ProductsRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<Category>> getCategories() async {
    final response = await apiClient.dio.get('/api/categories');
    return (response.data as List).map((e) => Category.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Backend returns a Spring Data Page (a JSON object with a "content"
  /// array), not a plain list - unlike /trending and /for-me below, which
  /// return plain lists. Mixing these up would silently break parsing.
  Future<List<Product>> getNewArrivals({int page = 0, int size = 10}) async {
    final response = await apiClient.dio.get(
      '/api/products/new-arrivals',
      queryParameters: {'page': page, 'size': size},
    );
    final content = response.data['content'] as List;
    return content.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<Product>> getTrending({int days = 7, int limit = 10}) async {
    final response = await apiClient.dio.get(
      '/api/recommendations/trending',
      queryParameters: {'days': days, 'limit': limit},
    );
    return (response.data as List).map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Requires login (personalized to the logged-in customer's own order
  /// history) - only call this when authenticated, or the backend correctly
  /// rejects it with a 401.
  Future<List<Product>> getRecommendedForMe({int limit = 10}) async {
    final response = await apiClient.dio.get(
      '/api/recommendations/for-me',
      queryParameters: {'limit': limit},
    );
    return (response.data as List).map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<Coupon>> getActiveOffers() async {
    final response = await apiClient.dio.get('/api/coupons/active');
    return (response.data as List).map((e) => Coupon.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<Product>> searchInstant(String keyword, {int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/products/search/instant',
      queryParameters: {'keyword': keyword, 'page': page, 'size': size},
    );
    final content = response.data['content'] as List;
    return content.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<Product>> browseByCategory(int categoryId, {int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/products/category/$categoryId',
      queryParameters: {'page': page, 'size': size},
    );
    final content = response.data['content'] as List;
    return content.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }
}
