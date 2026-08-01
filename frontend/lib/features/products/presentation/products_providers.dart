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
