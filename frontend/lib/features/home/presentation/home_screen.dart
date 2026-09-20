import '../../marketplace/presentation/marketplace_drawer.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../marketplace/domain/marketplace_feed_models.dart';
import '../../marketplace/presentation/marketplace_feed_provider.dart';
import '../../marketplace/presentation/marketplace_feed_section.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/widgets/brands_row.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/horizontal_product_section.dart';
import '../../../shared/widgets/offers_banner.dart';
import '../../../shared/widgets/scroll_to_top.dart';
import '../../../shared/widgets/section_load_error.dart';
import '../../../shared/widgets/see_all_products_screen.dart';
import '../../../shared/widgets/store_status_banner.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../brands/presentation/brands_screen.dart';
import '../../products/domain/product_models.dart';
import '../../products/presentation/brand_products_screen.dart';
import '../../products/presentation/product_detail_screen.dart';
import '../../products/presentation/product_feed_provider.dart';
import '../../products/presentation/products_providers.dart';
import 'home_feed_section.dart';
import 'home_header.dart';
import 'home_load_stage.dart';
import 'nearby_shops_section.dart';
import 'popular_categories.dart';

/// The first screen of a marketplace, not of a kirana.
///
/// WHAT CHANGED AND WHY. This screen used to draw, above the product feed, a
/// category tab bar, a category rail, a brand rail, an offers banner, a
/// bestseller collage, a second brand banner and three carousels - eight
/// surfaces, two of which led exactly where another one already led, before a
/// customer saw a single shop. It read as a grocery app because that is what
/// it was built for, and the weight was the reason it felt old rather than
/// the colours.
///
/// WHAT IS HERE NOW, IN ORDER: where you are and what you can search; seven
/// categories and a way to the rest; the shops near you; then offers and the
/// curated rails, each of which draws NOTHING when its data is absent (§8).
/// The endless catalogue feed still ends the page, unchanged.
///
/// THE TWO-WAVE LOAD SURVIVED INTACT and is the reason this stays fast. The
/// categories and offers are in flight the moment the screen opens; the
/// carousels and the feed wait behind homeBelowFoldReadyProvider, because a
/// request for something below the fold competing with content the customer
/// can see is a slower first paint for nothing.
class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // NOTHING THAT MERELY ARRIVES IS WATCHED AT THIS LEVEL. Categories,
    // offers and brands are each watched inside their own Consumer below, so
    // when one of them lands only that section rebuilds. Watched here - as
    // they were - every arrival rebuilt the whole page, including the
    // CustomScrollView and the sliver list under it, three times on a cold
    // open (§12: do not rebuild the entire home screen unnecessarily).
    //
    // The gate below IS watched here, because it changes what the page is
    // allowed to request and that is a decision about the page rather than
    // about one section.
    final belowFoldReady = ref.watch(homeBelowFoldReadyProvider);
    // Watched HERE rather than inside HomeFeedSlivers.build, which runs inside
    // ScrollToTop's builder callback and so executes during ScrollToTop's
    // build rather than this one.
    // WHICH FEED THIS SCREEN IS. On a marketplace the home screen shows the
    // TOWN - every shop that would serve this customer - because requiring
    // somebody to pick a shop before they can see a product is what made
    // "All Products" show one kirana's shelf, or nothing at all. Under a
    // single shop the two questions have the same answer, so the older
    // shop-scoped feed stays and nothing changes for that deployment.
    //
    // Browsing one storefront on purpose is unaffected either way: that is
    // the shop screen, and it is still there.
    final onAMarketplace = ref.watch(isMarketplaceProvider);
    final feedAsync = belowFoldReady && !onAMarketplace
        ? ref.watch(productFeedProvider)
        : const AsyncValue<ProductFeedState>.loading();
    final marketplaceAsync = belowFoldReady && onAMarketplace
        ? ref.watch(marketplaceFeedProvider)
        : const AsyncValue<MarketplaceFeedState>.loading();
    final isLoggedIn = ref.watch(authControllerProvider).status == AuthStatus.authenticated;

    void openProduct(Product product) => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => ProductDetailScreen(product: product)),
        );

    // A marketplace card carries a product ID rather than a whole product -
    // the feed deliberately does not pay for every product's full detail to
    // draw a grid. So the detail is fetched when one is actually opened.
    Future<void> openMarketplaceCard(MarketplaceCard card) async {
      try {
        final product = await ref
            .read(productsRepositoryProvider)
            .fetchProductDetail(card.productId);
        if (!context.mounted) return;
        openProduct(product);
      } catch (e) {
        if (!context.mounted) return;
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
      }
    }

    // ADD IS ONLY EVER CALLED FOR A CARD THE SERVER SAID IS ADDABLE - the
    // tile passes null otherwise - and the backend refuses a Visit-to-Buy or
    // a service anyway. Two doors, because a hidden button is a courtesy
    // rather than a control.
    Future<void> addFromMarketplace(MarketplaceCard card) async {
      final variantId = card.productVariantId;
      if (variantId == null) return;
      try {
        await ref
            .read(cartControllerProvider.notifier)
            .addToCart(variantId: variantId, quantity: 1);
        if (!context.mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('${card.name} added to cart')),
        );
      } catch (e) {
        if (!context.mounted) return;
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
      }
    }

    return Scaffold(
      // THE DRAWER IS ONLY ON A MARKETPLACE. Under a single shop there are no
      // modes to switch between, and an entry labelled "Visit to Buy" that
      // leads to an empty feed is the dead end this app is careful not to
      // build.
      drawer: onAMarketplace ? const MarketplaceDrawer() : null,
      bottomNavigationBar: const CartSummaryBar(),
      body: Column(
        children: [
          // OUTSIDE THE SCROLL VIEW, deliberately. Search and the cart are
          // wanted at any scroll position, and a header that scrolls away is a
          // header a customer scrolls back up to find.
          const HomeHeader(),
          Expanded(
            child: RefreshIndicator(
              // ref.refresh(...future) rather than invalidate for the sections
              // that are already on screen: invalidate returns immediately, so
              // the spinner would dismiss itself before the new data arrived.
              onRefresh: () => Future.wait([
                ref.refresh(categoriesProvider.future),
                ref.refresh(activeOffersProvider.future),
                // INVALIDATE, not refresh, for the gated sections: refresh
                // READS the provider, which builds it and fires exactly the
                // request the gate is holding back. invalidate only marks
                // them stale.
                Future.sync(() => ref.invalidate(marketCategoriesProvider)),
                Future.sync(() => ref.invalidate(newArrivalsProvider)),
                Future.sync(() => ref.invalidate(trendingProvider)),
                Future.sync(() => ref.invalidate(recommendedForMeProvider)),
                Future.sync(() => ref.invalidate(onAMarketplace
                    ? marketplaceFeedProvider
                    : productFeedProvider)),
              ]),
              child: ScrollToTop(
                builder: (context, scrollController) => NotificationListener<ScrollNotification>(
                  onNotification: (notification) {
                    // Trigger a page BEFORE the customer hits the bottom, so
                    // the next products are usually already there by the time
                    // they arrive.
                    //
                    // Only depth 0: a horizontal carousel inside the page also
                    // emits ScrollNotifications, and without this check
                    // flicking "Trending now" sideways would request another
                    // page of the vertical feed. belowFoldReady as well:
                    // reading .notifier would BUILD the feed provider and fire
                    // its first page, which is what the gate exists to hold
                    // back.
                    if (belowFoldReady &&
                        notification.depth == 0 &&
                        notification.metrics.axis == Axis.vertical &&
                        notification.metrics.extentAfter < 600) {
                      if (onAMarketplace) {
                        ref.read(marketplaceFeedProvider.notifier).loadMore();
                      } else {
                        ref.read(productFeedProvider.notifier).loadMore();
                      }
                    }
                    // false: this listener observes, it does not consume.
                    return false;
                  },
                  child: CustomScrollView(
                    controller: scrollController,
                    slivers: [
                      SliverList(
                        delegate: SliverChildListDelegate([
                          // Renders nothing at all during normal hours - see
                          // the widget. At 20:50 "closes in 10 min" is the
                          // most useful thing on this screen.
                          const StoreStatusBanner(),
                          const PopularCategories(),
                          const NearbyShopsSection(),

                          Consumer(
                            builder: (context, ref, _) => ref.watch(activeOffersProvider).when(
                                  loading: () => const SizedBox.shrink(),
                                  error: (e, s) => SectionLoadError(
                                    message: "Couldn't load offers",
                                    onRetry: () => ref.invalidate(activeOffersProvider),
                                  ),
                                  data: (offers) => offers.isEmpty
                                      // §8: no section for data that is not
                                      // there. An empty offers banner is a
                                      // heading over a blank strip.
                                      ? const SizedBox.shrink()
                                      : Padding(
                                          padding: const EdgeInsets.only(top: 8),
                                          child: OffersBanner(offers: offers),
                                        ),
                                ),
                          ),

                          // SECOND WAVE, from here down. Each section is a
                          // Consumer that watches its provider only once the
                          // gate is open - and a Riverpod watch inside a
                          // builder is genuinely conditional, so an unwatched
                          // provider is never built and never issues its
                          // request.
                          if (isLoggedIn)
                            Consumer(
                              builder: (context, ref, _) => HorizontalProductSection(
                                title: 'Recommended for you',
                                provider: belowFoldReady
                                    ? ref.watch(recommendedForMeProvider)
                                    : const AsyncValue.loading(),
                                onRetry: () => ref.invalidate(recommendedForMeProvider),
                                onProductTap: openProduct,
                                onSeeAllTap: () => Navigator.of(context).push(
                                  MaterialPageRoute(
                                    builder: (_) => SeeAllProductsScreen(
                                      title: 'Recommended for you',
                                      fetchProducts: () => ref
                                          .read(productsRepositoryProvider)
                                          .getRecommendedForMe(limit: 50),
                                    ),
                                  ),
                                ),
                              ),
                            ),

                          Consumer(
                            builder: (context, ref, _) => HorizontalProductSection(
                              title: 'Trending now',
                              provider: belowFoldReady
                                  ? ref.watch(trendingProvider)
                                  : const AsyncValue.loading(),
                              onRetry: () => ref.invalidate(trendingProvider),
                              onProductTap: openProduct,
                              onSeeAllTap: () => Navigator.of(context).push(
                                MaterialPageRoute(
                                  builder: (_) => SeeAllProductsScreen(
                                    title: 'Trending now',
                                    fetchProducts: () =>
                                        ref.read(productsRepositoryProvider).getTrending(limit: 50),
                                  ),
                                ),
                              ),
                            ),
                          ),

                          Consumer(
                            builder: (context, ref, _) => ref.watch(brandsProvider).when(
                                  loading: () => const SizedBox.shrink(),
                                  error: (e, s) => SectionLoadError(
                                    message: "Couldn't load brands",
                                    onRetry: () => ref.invalidate(brandsProvider),
                                  ),
                                  data: (brands) => BrandsRow(
                                    brands: brands,
                                    onBrandTap: (brand) => Navigator.of(context).push(
                                      MaterialPageRoute(
                                          builder: (_) => BrandProductsScreen(brand: brand)),
                                    ),
                                    // THE ONLY WAY TO THE FULL BRAND LIST now that
                                    // the second brand banner is gone. Removing a
                                    // surface must not orphan a screen.
                                    onSeeAll: () => Navigator.of(context).push(
                                      MaterialPageRoute(builder: (_) => const BrandsScreen()),
                                    ),
                                  ),
                                ),
                          ),

                          Consumer(
                            builder: (context, ref, _) => HorizontalProductSection(
                              title: 'New arrivals',
                              provider: belowFoldReady
                                  ? ref.watch(newArrivalsProvider)
                                  : const AsyncValue.loading(),
                              onRetry: () => ref.invalidate(newArrivalsProvider),
                              onProductTap: openProduct,
                              onSeeAllTap: () => Navigator.of(context).push(
                                MaterialPageRoute(
                                  builder: (_) => SeeAllProductsScreen(
                                    title: 'New arrivals',
                                    fetchProducts: () => ref
                                        .read(productsRepositoryProvider)
                                        .getNewArrivals(size: 50),
                                  ),
                                ),
                              ),
                            ),
                          ),

                          const SizedBox(height: 8),
                        ]),
                      ),
                      // Everything above is the curated part of the home
                      // screen. This is where it stops ending after New
                      // arrivals and keeps going through the whole catalogue,
                      // one page at a time.
                      if (onAMarketplace)
                        ...MarketplaceFeedSlivers.build(context, ref,
                            feed: marketplaceAsync,
                            onCardTap: openMarketplaceCard,
                            onAdd: addFromMarketplace)
                      else
                        ...HomeFeedSlivers.build(context, ref,
                            feed: feedAsync, onProductTap: openProduct),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
