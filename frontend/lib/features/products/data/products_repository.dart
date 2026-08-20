import '../../../core/api/api_client.dart';
import '../domain/brand_models.dart';
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

  /// "Customers who bought this also bought..." - real co-purchase data,
  /// not a static/manual pairing.
  Future<List<Product>> getFrequentlyBoughtTogether(int productId, {int limit = 5}) async {
    final response = await apiClient.dio.get(
      '/api/recommendations/frequently-bought-together/$productId',
      queryParameters: {'limit': limit},
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

  /// The full product, including its image gallery.
  ///
  /// A separate call rather than reusing the Product the list screen already
  /// has: list responses carry no gallery by design, so opening a product
  /// from a grid has no images to show beyond the variant thumbnail. This is
  /// the "load the full gallery when the detail page opens" half of that
  /// trade.
  Future<Product> fetchProductDetail(int productId) async {
    final response = await apiClient.dio.get('/api/products/$productId');
    return Product.fromJson(response.data as Map<String, dynamic>);
  }

  Future<List<Product>> browseByCategory(int categoryId, {int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/products/category/$categoryId',
      queryParameters: {'page': page, 'size': size},
    );
    final content = response.data['content'] as List;
    return content.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Only brands with at least one active product right now - guaranteed
  /// server-side, no empty-brand filtering needed here.
  Future<List<BrandSummary>> getBrands() async {
    final response = await apiClient.dio.get('/api/products/brands');
    return (response.data as List).map((e) => BrandSummary.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Returns the page content plus total counts, so the UI can show
  /// "Load more" only when there's genuinely more to fetch.
  Future<({List<Product> products, int totalElements, int totalPages})> browseByBrand({
    required String brand,
    BrandSortOption? sort,
    bool inStockOnly = false,
    String? keyword,
    int page = 0,
    int size = 20,
  }) async {
    final response = await apiClient.dio.get(
      '/api/products/brand/$brand',
      queryParameters: {
        if (sort != null) 'sort': sort.apiValue,
        'inStockOnly': inStockOnly,
        if (keyword != null && keyword.isNotEmpty) 'keyword': keyword,
        'page': page,
        'size': size,
      },
    );

    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;

    return (
      products: content.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList(),
      totalElements: data['totalElements'] as int,
      totalPages: data['totalPages'] as int,
    );
  }

  /// Same sort/filter/search capability as browseByBrand, for category
  /// browsing - a separate endpoint from the plain paginated one, so
  /// nothing already using that simpler version breaks.
  Future<({List<Product> products, int totalElements, int totalPages})> browseByCategoryFiltered({
    required int categoryId,
    BrandSortOption? sort,
    bool inStockOnly = false,
    String? keyword,
    int page = 0,
    int size = 20,
  }) async {
    final response = await apiClient.dio.get(
      '/api/products/category/$categoryId/filtered',
      queryParameters: {
        if (sort != null) 'sort': sort.apiValue,
        'inStockOnly': inStockOnly,
        if (keyword != null && keyword.isNotEmpty) 'keyword': keyword,
        'page': page,
        'size': size,
      },
    );

    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;

    return (
      products: content.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList(),
      totalElements: data['totalElements'] as int,
      totalPages: data['totalPages'] as int,
    );
  }
}
