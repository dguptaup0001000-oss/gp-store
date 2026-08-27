import 'package:dio/dio.dart';
import '../../../core/api/api_client.dart';
import '../domain/bestseller_models.dart';
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

  /// The whole Bestsellers collage in ONE request.
  ///
  /// This replaces six. The collage called browseByCategory once per tile,
  /// so opening the app cost six HTTP round trips - six TLS handshakes, six
  /// auth filter chains, six database connection acquisitions - to render
  /// twenty-four thumbnails. The backend now assembles it in a single SQL
  /// statement and returns only the fields the tile actually draws.
  ///
  /// The defaults are what the UI draws rather than a page size borrowed
  /// from another screen: six tiles of four, not six pages of twenty.
  Future<List<BestsellerTile>> getBestsellerTiles({int categories = 6, int perCategory = 4}) async {
    final response = await apiClient.dio.get(
      '/api/products/bestsellers',
      queryParameters: {'categories': categories, 'perCategory': perCategory},
    );
    return (response.data as List)
        .map((e) => BestsellerTile.fromJson(e as Map<String, dynamic>))
        .where((tile) => tile.isRenderable)
        .toList();
  }

  Future<List<Coupon>> getActiveOffers() async {
    final response = await apiClient.dio.get('/api/coupons/active');
    return (response.data as List)
        .whereType<Map>()
        .map((e) {
          try {
            return Coupon.fromJson(Map<String, dynamic>.from(e));
          } catch (_) {
            return null;
          }
        })
        .whereType<Coupon>()
        .toList();
  }

  Future<List<Product>> searchInstant(String keyword, {int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/products/search/instant',
      queryParameters: {'keyword': keyword, 'page': page, 'size': size},
    );
    final content = response.data['content'] as List;
    return content.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Smart Search: typo-tolerant, Hinglish-aware, and it reports what it
  /// understood.
  ///
  /// [interpretedAs] is high confidence - the backend searched for this
  /// instead of what was typed, and the UI should say so. [didYouMean] is low
  /// confidence - the customer's own words were kept and this is offered as a
  /// suggestion. Both null is the ordinary case of a search that worked as
  /// typed, and the UI should stay quiet then.
  /// Returns the paging metadata as well as the products.
  ///
  /// The backend has always sent totalPages/number; this used to throw them
  /// away, so the search screen showed the first 20 matches and had no way
  /// to know - or to say - that there were more. A customer searching a
  /// broad term like "atta" simply never saw result 21.
  Future<({
    List<Product> products,
    String? interpretedAs,
    String? didYouMean,
    bool hasMore,
  })> searchSmart(
    String keyword, {
    int page = 0,
    int size = 20,
    CancelToken? cancelToken,
  }) async {
    final response = await apiClient.dio.get(
      '/api/products/search/smart',
      queryParameters: {'keyword': keyword, 'page': page, 'size': size},
      // Passed through so a superseded search is actually aborted rather than
      // merely having its answer discarded - the difference matters to the
      // backend, which otherwise pays for every keystroke that got overtaken.
      cancelToken: cancelToken,
    );

    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;

    // Derived from the server's own totals rather than from "did this page
    // come back full": a page can be short for other reasons, and guessing
    // either hides results or offers a Load more that returns nothing.
    final totalPages = (data['totalPages'] as num?)?.toInt() ?? 1;
    final currentPage = (data['number'] as num?)?.toInt() ?? page;

    return (
      products: content.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList(),
      interpretedAs: data['interpretedAs'] as String?,
      didYouMean: data['didYouMean'] as String?,
      hasMore: currentPage + 1 < totalPages,
    );
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

  /// One page of the endless home feed.
  ///
  /// Returns [hasNext] rather than only the products, because without it the
  /// client cannot tell "this page happened to be short" from "there is
  /// nothing more" - and a client that cannot tell keeps requesting page
  /// after empty page forever. Spring's Page JSON already carries `last`;
  /// every other method in this file throws it away and returns a bare
  /// List, which is exactly why none of them can drive infinite scroll.
  Future<ProductPage> fetchFeed({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/products/feed',
      queryParameters: {'page': page, 'size': size},
    );

    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;

    return ProductPage(
      products: content.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList(),
      page: (data['number'] as int?) ?? page,
      // Trust the server's own "is this the last page" flag rather than
      // inferring it from a short page: a page can be short for other
      // reasons, and inferring wrongly either stops early (products the
      // customer never sees) or never stops at all.
      hasNext: !((data['last'] as bool?) ?? true),
      totalElements: (data['totalElements'] as int?) ?? content.length,
    );
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

/// One page of products plus the two facts infinite scroll needs: which page
/// this was, and whether another exists.
class ProductPage {
  const ProductPage({
    required this.products,
    required this.page,
    required this.hasNext,
    required this.totalElements,
  });

  final List<Product> products;
  final int page;
  final bool hasNext;
  final int totalElements;
}
