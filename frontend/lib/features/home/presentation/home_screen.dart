import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../cart/presentation/cart_screen.dart';
import '../../orders/presentation/order_history_screen.dart';
import '../../products/domain/product_models.dart';
import '../../products/presentation/brand_products_screen.dart';
import '../../products/presentation/category_products_screen.dart';
import '../../products/presentation/product_detail_screen.dart';
import '../../products/presentation/products_providers.dart';
import '../../products/presentation/search_screen.dart';
import '../../profile/presentation/profile_screen.dart';
import '../../../shared/widgets/categories_row.dart';
import '../../../shared/widgets/offers_banner.dart';
import '../../../shared/widgets/brands_row.dart';
import '../../../shared/widgets/category_tabs_bar.dart';
import '../../../shared/widgets/bestsellers_section.dart';
import '../../../shared/widgets/buy_by_brand_banner.dart';
import '../../../shared/widgets/horizontal_product_section.dart';
import '../../../shared/widgets/see_all_products_screen.dart';
import '../../../shared/widgets/cart_summary_bar.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categoriesAsync = ref.watch(categoriesProvider);
    final brandsAsync = ref.watch(brandsProvider);
    final offersAsync = ref.watch(activeOffersProvider);
    final newArrivalsAsync = ref.watch(newArrivalsProvider);
    final trendingAsync = ref.watch(trendingProvider);
    final recommendedAsync = ref.watch(recommendedForMeProvider);
    final isLoggedIn = ref.watch(authControllerProvider).status == AuthStatus.authenticated;
    final cartItemCount = ref.watch(cartControllerProvider).valueOrNull?.totalItems ?? 0;

    void openProduct(Product product) => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => ProductDetailScreen(product: product)),
        );

    return Scaffold(
      appBar: AppBar(
        title: const Text('GP-Store', style: TextStyle(fontWeight: FontWeight.w800)),
        actions: [
          IconButton(
            icon: const Icon(Icons.receipt_long_outlined),
            tooltip: 'My Orders',
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const OrderHistoryScreen()),
            ),
          ),
          Stack(
            alignment: Alignment.center,
            children: [
              IconButton(
                icon: const Icon(Icons.shopping_cart_outlined),
                tooltip: 'Cart',
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const CartScreen()),
                ),
              ),
              if (cartItemCount > 0)
                Positioned(
                  top: 6,
                  right: 6,
                  child: Container(
                    padding: const EdgeInsets.all(3),
                    decoration: const BoxDecoration(color: AppColors.primary, shape: BoxShape.circle),
                    constraints: const BoxConstraints(minWidth: 16, minHeight: 16),
                    child: Text(
                      '$cartItemCount',
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700),
                    ),
                  ),
                ),
            ],
          ),
          IconButton(
            icon: const Icon(Icons.person_outline),
            tooltip: 'Profile',
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const ProfileScreen()),
            ),
          ),
        ],
      ),
      bottomNavigationBar: const CartSummaryBar(),
      body: RefreshIndicator(
        // ref.invalidate() alone doesn't return anything tied to the actual
        // refetch completing - it just marks each provider dirty and
        // returns immediately, so RefreshIndicator's spinner was dismissing
        // itself before the new data had even come back. ref.refresh(...future)
        // both invalidates AND returns the new Future, which is what
        // RefreshIndicator needs to know when the pull-to-refresh is
        // actually done.
        onRefresh: () => Future.wait([
          ref.refresh(categoriesProvider.future),
          ref.refresh(activeOffersProvider.future),
          ref.refresh(newArrivalsProvider.future),
          ref.refresh(trendingProvider.future),
          ref.refresh(recommendedForMeProvider.future),
        ]),
        child: ListView(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: GestureDetector(
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const SearchScreen()),
                ),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  decoration: BoxDecoration(
                    color: AppColors.cardBackground,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Row(
                    children: [
                      Icon(Icons.search, color: AppColors.textSecondary),
                      SizedBox(width: 8),
                      Text('Search for atta, milk, snacks...', style: TextStyle(color: AppColors.textSecondary)),
                    ],
                  ),
                ),
              ),
            ),

            categoriesAsync.when(
              loading: () => const SizedBox.shrink(),
              error: (e, s) => const SizedBox.shrink(),
              data: (categories) => Padding(
                padding: const EdgeInsets.only(top: 12),
                child: CategoryTabsBar(categories: categories),
              ),
            ),

            categoriesAsync.when(
              loading: () => const SizedBox(height: 96, child: Center(child: CircularProgressIndicator(strokeWidth: 2))),
              error: (e, s) => const SizedBox.shrink(),
              data: (categories) => Padding(
                padding: const EdgeInsets.only(top: 12),
                child: CategoriesRow(
                  categories: categories,
                  onCategoryTap: (category) => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => CategoryProductsScreen(category: category)),
                  ),
                ),
              ),
            ),

            brandsAsync.when(
              loading: () => const SizedBox.shrink(),
              error: (e, s) => const SizedBox.shrink(),
              data: (brands) => BrandsRow(
                brands: brands,
                onBrandTap: (brand) => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => BrandProductsScreen(brand: brand)),
                ),
              ),
            ),

            offersAsync.when(
              loading: () => const SizedBox.shrink(),
              error: (e, s) => const SizedBox.shrink(),
              data: (offers) => Padding(
                padding: const EdgeInsets.only(top: 16),
                child: OffersBanner(offers: offers),
              ),
            ),

            categoriesAsync.when(
              loading: () => const SizedBox.shrink(),
              error: (e, s) => const SizedBox.shrink(),
              data: (categories) => BestsellersSection(categories: categories),
            ),

            brandsAsync.when(
              loading: () => const SizedBox.shrink(),
              error: (e, s) => const SizedBox.shrink(),
              data: (brands) => BuyByBrandBanner(brands: brands),
            ),

            if (isLoggedIn)
              HorizontalProductSection(
                title: 'Recommended for you',
                provider: recommendedAsync,
                onRetry: () => ref.invalidate(recommendedForMeProvider),
                onProductTap: openProduct,
                onSeeAllTap: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => SeeAllProductsScreen(
                      title: 'Recommended for you',
                      fetchProducts: () => ref.read(productsRepositoryProvider).getRecommendedForMe(limit: 50),
                    ),
                  ),
                ),
              ),

            HorizontalProductSection(
              title: 'Trending now',
              provider: trendingAsync,
              onRetry: () => ref.invalidate(trendingProvider),
              onProductTap: openProduct,
              onSeeAllTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => SeeAllProductsScreen(
                    title: 'Trending now',
                    fetchProducts: () => ref.read(productsRepositoryProvider).getTrending(limit: 50),
                  ),
                ),
              ),
            ),

            HorizontalProductSection(
              title: 'New Arrivals',
              provider: newArrivalsAsync,
              onRetry: () => ref.invalidate(newArrivalsProvider),
              onProductTap: openProduct,
              onSeeAllTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => SeeAllProductsScreen(
                    title: 'New Arrivals',
                    fetchProducts: () => ref.read(productsRepositoryProvider).getNewArrivals(size: 50),
                  ),
                ),
              ),
            ),

            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }
}
