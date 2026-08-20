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
