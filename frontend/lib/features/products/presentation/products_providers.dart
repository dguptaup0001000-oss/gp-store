import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/products_repository.dart';
import '../domain/brand_models.dart';
import '../domain/product_models.dart';

final productsRepositoryProvider = Provider<ProductsRepository>((ref) {
  return ProductsRepository(apiClient: ref.watch(apiClientProvider));
});

final categoriesProvider = FutureProvider<List<Category>>((ref) {
  return ref.watch(productsRepositoryProvider).getCategories();
});

final brandsProvider = FutureProvider<List<BrandSummary>>((ref) {
  return ref.watch(productsRepositoryProvider).getBrands();
});

final activeOffersProvider = FutureProvider<List<Coupon>>((ref) {
  return ref.watch(productsRepositoryProvider).getActiveOffers();
});

final newArrivalsProvider = FutureProvider<List<Product>>((ref) {
  return ref.watch(productsRepositoryProvider).getNewArrivals();
});

/// Full product detail, including the image gallery.
///
/// autoDispose so a browsing session does not accumulate every product the
/// customer looked at; family so each product is cached independently while
/// its page is open.
final productDetailProvider = FutureProvider.autoDispose.family<Product, int>((ref, productId) {
  return ref.watch(productsRepositoryProvider).fetchProductDetail(productId);
});

/// Other products in the same category - the "Similar products" strip.
///
/// Category rather than frequently-bought-together: co-purchase data is
/// empty for most of a young catalogue, so that strip would be blank on
/// nearly every product. Same-category always has something to show and is
/// genuinely "similar".
final similarProductsProvider =
    FutureProvider.autoDispose.family<List<Product>, ({int categoryId, int excludeProductId})>((ref, args) async {
  final products = await ref
      .watch(productsRepositoryProvider)
      .browseByCategory(args.categoryId, page: 0, size: 12);
  return products.where((p) => p.id != args.excludeProductId).toList();
});

/// A few products from one category, for the Bestsellers collage.
///
/// A PROVIDER RATHER THAN AN INLINE FutureBuilder, and the difference is not
/// stylistic. The collage previously built its future inside build():
///
///   FutureBuilder(future: repository.browseByCategory(id, size: 4), ...)
///
/// A future constructed during build is a NEW future on every build, so
/// every rebuild of the home screen re-issued the request - once per
/// category tile. Six categories meant six requests on open and six more on
/// each rebuild, for a collage whose contents had not changed. That is the
/// per-category request fan-out the load test showed.
///
/// Riverpod caches by argument, so each category is fetched once and shared.
/// Not autoDispose: the home screen is the app's landing surface and is
/// returned to constantly; dropping the cache on every navigation away would
/// re-fetch all six on every return, which is the problem again in a
/// different costume.
final categoryPreviewProvider = FutureProvider.family<List<Product>, int>((ref, categoryId) {
  return ref.watch(productsRepositoryProvider).browseByCategory(categoryId, size: 4);
});

final trendingProvider = FutureProvider<List<Product>>((ref) {
  return ref.watch(productsRepositoryProvider).getTrending();
});

final frequentlyBoughtTogetherProvider = FutureProvider.family<List<Product>, int>((ref, productId) {
  return ref.watch(productsRepositoryProvider).getFrequentlyBoughtTogether(productId);
});

/// Only meaningful for a logged-in customer (personalized to THEIR order
/// history) - the home screen only shows this section when authenticated,
/// rather than calling an endpoint that would correctly reject it.
final recommendedForMeProvider = FutureProvider<List<Product>>((ref) {
  final authState = ref.watch(authControllerProvider);
  if (authState.status != AuthStatus.authenticated) {
    return Future.value(<Product>[]);
  }
  return ref.watch(productsRepositoryProvider).getRecommendedForMe();
});
