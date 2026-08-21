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
import '../../products/presentation/product_feed_provider.dart';
import '../../products/presentation/products_providers.dart';
import 'home_feed_section.dart';
import 'home_load_stage.dart';
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
import '../../../shared/widgets/scroll_to_top.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categoriesAsync = ref.watch(categoriesProvider);
    final brandsAsync = ref.watch(brandsProvider);
    final offersAsync = ref.watch(activeOffersProvider);
    // The FIRST WAVE. Categories, brands and offers are drawn at or just
    // below the fold, so they have to be in flight the moment the screen
    // opens. The three carousels and the endless feed are not, and they wait
    // for this wave to settle - see homeBelowFoldReadyProvider.
    final belowFoldReady = ref.watch(homeBelowFoldReadyProvider);
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
          // INVALIDATE, not refresh, for the gated sections.
          // ref.refresh(p.future) READS the provider, which builds it and
          // fires its request - so a pull-to-refresh arriving before the
          // gate opened would put on the wire exactly the three requests the
          // gate is holding back. invalidate only marks them stale: a
          // section already loaded refetches, one still behind the gate
          // stays unbuilt and costs nothing.
          Future.sync(() => ref.invalidate(newArrivalsProvider)),
          Future.sync(() => ref.invalidate(trendingProvider)),
          Future.sync(() => ref.invalidate(recommendedForMeProvider)),
          // Pull-to-refresh restarts the feed at page 0 rather than leaving
          // the customer's accumulated pages in place - otherwise "refresh"
          // updates the carousels while the feed below still shows the
          // catalogue as it was.
          Future.sync(() => ref.invalidate(productFeedProvider)),
        ]),
        // CustomScrollView rather than ListView, because the endless product
        // feed is appended below as SLIVERS. A GridView nested inside a
        // ListView would need shrinkWrap, which builds every tile at once -
        // with a catalogue of thousands that is the entire list in memory
        // and a scroll that visibly stutters. Sharing one viewport means
        // only what is on screen gets built, however long the feed grows.
        // NotificationListener rather than a ScrollController: there is no
        // controller to create, hold, or forget to dispose, and this widget
        // does not otherwise need one. It fires on the scroll events the
        // page is already producing.
        // The controller is only for Back to top; the infinite feed below
        // still drives itself from scroll notifications, so the two do not
        // interfere and neither had to be rewritten for the other.
        child: ScrollToTop(
          builder: (context, scrollController) => NotificationListener<ScrollNotification>(
          onNotification: (notification) {
            // Trigger a page BEFORE the customer hits the bottom, so the
            // next products are usually already there by the time they
            // arrive - waiting until extentAfter == 0 guarantees they see
            // the spinner every single time.
            //
            // Only depth 0: a horizontal carousel inside the page also emits
            // ScrollNotifications, and without this check flicking "Trending
            // now" sideways would request another page of the vertical feed.
            // belowFoldReady as well: reading .notifier would BUILD the
            // feed provider and fire its first page, which is the one thing
            // the gate exists to hold back.
            if (belowFoldReady &&
                notification.depth == 0 &&
                notification.metrics.axis == Axis.vertical &&
                notification.metrics.extentAfter < 600) {
              // loadMore is safe to call repeatedly - it no-ops while a page
              // is in flight and once the server says there is no next page.
              ref.read(productFeedProvider.notifier).loadMore();
            }
            // false: this listener observes, it does not consume. Returning
            // true would swallow the notification and break anything else
            // listening, including the refresh indicator.
            return false;
          },
          child: CustomScrollView(
            controller: scrollController,
            slivers: [
            SliverList(
              delegate: SliverChildListDelegate([
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: GestureDetector(
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const SearchScreen()),
                ),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                  decoration: BoxDecoration(
                    color: AppColors.cardBackground,
                    // Fully rounded and lifted: the search box is the first
                    // thing a customer looks for, and on a lavender ground a
                    // flat cream rectangle recedes rather than inviting a tap.
                    borderRadius: BorderRadius.circular(28),
                    boxShadow: AppElevation.card,
                  ),
                  child: const Row(
                    children: [
                      Icon(Icons.search, color: AppColors.textSecondary),
                      SizedBox(width: 8),
                      Text('Search for atta, dal, milk, snacks...', style: TextStyle(color: AppColors.textSecondary)),
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

            // SECOND WAVE, from here down.
            //
            // Each carousel is a Consumer that watches its provider only
            // once the gate is open - and a Riverpod watch inside a builder
            // is genuinely conditional, so an unwatched provider is never
            // built and never issues its request. Until then the section
            // renders its own loading state, which is what it would be
            // showing anyway while the request was in flight, so the page
            // looks no different and nothing moves when the data lands.
            //
            // Watching these three at the top of HomeScreen.build instead -
            // as this file did - put all three on the wire at open,
            // competing with the categories, brands and offers calls for
            // content the customer can actually see.
            //
            // The providers are not autoDispose, so once a section has
            // loaded, scrolling past it and back does not refetch.
            if (isLoggedIn)
              Consumer(
                builder: (context, ref, _) => HorizontalProductSection(
                  title: 'Recommended for you',
                  provider: belowFoldReady ? ref.watch(recommendedForMeProvider) : const AsyncValue.loading(),
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
              ),

            Consumer(
              builder: (context, ref, _) => HorizontalProductSection(
                title: 'Trending now',
                provider: belowFoldReady ? ref.watch(trendingProvider) : const AsyncValue.loading(),
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
            ),

            Consumer(
              builder: (context, ref, _) => HorizontalProductSection(
                title: 'New Arrivals',
                provider: belowFoldReady ? ref.watch(newArrivalsProvider) : const AsyncValue.loading(),
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
            ),

              const SizedBox(height: 8),
            ]),
            ),
            // Everything above is the curated part of the home screen. This
            // is where it stops ending after New Arrivals and keeps going
            // through the whole catalogue, one page at a time.
            ...HomeFeedSlivers.build(context, ref, ready: belowFoldReady, onProductTap: openProduct),
            ],
          ),
        ),
        ),
      ),
    );
  }
}
