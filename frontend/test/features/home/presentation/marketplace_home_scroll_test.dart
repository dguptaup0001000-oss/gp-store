import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_feed_models.dart';
import 'package:gpstore/features/marketplace/presentation/marketplace_feed_provider.dart';
import 'package:gpstore/features/marketplace/presentation/marketplace_feed_section.dart';
import 'package:gpstore/features/wishlist/domain/wishlist_models.dart';
import 'package:gpstore/features/wishlist/presentation/wishlist_providers.dart';

void main() {
  const pin = (lat: 26.45, lng: 80.33);
  final fixture = <MarketplaceCard>[
    for (final mode in CommerceMode.values)
      MarketplaceCard(
        productId: mode.index + 1,
        name: 'Test ${mode.label}',
        commerceMode: mode,
        priceMode: ListingPriceMode.exact,
        addable: mode == CommerceMode.buyOnline,
        sellingPrice: 20,
        shopId: 1,
        shopName: 'Test shop',
      ),
  ];

  Widget host({bool buyLoading = false, bool buyError = false, bool buyEmpty = false}) {
    final overrides = <Override>[
      deliveryPinProvider.overrideWith((ref) => pin),
      wishlistControllerProvider.overrideWith(_EmptyWishlistController.new),
      for (final mode in CommerceMode.values)
        marketplaceHomeModeFeedProvider(mode).overrideWith((ref) {
          final card = fixture.singleWhere((item) => item.commerceMode == mode);
          final isBuy = mode == CommerceMode.buyOnline;
          return _PresetModeController(
            ref: ref,
            mode: mode,
            state: MarketplaceHomeModeFeedState(
              cards: isBuy && (buyEmpty || buyError) ? const [] : [card],
              isLoading: isBuy && buyLoading,
              error: isBuy && buyError ? Exception('test failure') : null,
              hasNext: false,
            ),
          );
        }),
      marketplaceHomeAllFeedProvider.overrideWith((ref) =>
          _PresetAllController(ref: ref, state: const MarketplaceHomeAllFeedState(isLoading: false))),
    ];

    return ProviderScope(
      overrides: overrides,
      child: MaterialApp(
        home: Scaffold(
          body: CustomScrollView(
            slivers: [
              const SliverToBoxAdapter(child: Text('All nearby shops')),
              ...MarketplaceFeedSlivers.homeSections(
                selectedMode: null,
                onCardTap: (_) {},
                onAdd: (_) {},
              ),
              const SliverToBoxAdapter(child: Text('New arrivals')),
              const SliverToBoxAdapter(child: Text('Recommended for you')),
              MarketplaceAllProductsSliver(onCardTap: (_) {}, onAdd: (_) {}),
              const SliverToBoxAdapter(child: SizedBox(height: 24)),
            ],
          ),
          bottomNavigationBar: const SizedBox(height: 48, child: Text('Home · Categories · Orders · Profile')),
        ),
      ),
    );
  }

  Future<void> reachLowerSections(WidgetTester tester) async {
    final scrollable = find.byWidgetPredicate(
      (widget) => widget is Scrollable && widget.axisDirection == AxisDirection.down,
    ).first;
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey<String>('marketplace-section-Visit to Buy')),
      220,
      scrollable: scrollable,
    );
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey<String>('marketplace-section-Services at Shop')),
      220,
      scrollable: scrollable,
    );
    await tester.scrollUntilVisible(find.text('New arrivals'), 220, scrollable: scrollable);
    await tester.scrollUntilVisible(find.text('Recommended for you'), 220, scrollable: scrollable);
    await tester.scrollUntilVisible(
      find.text('All nearby products and services'),
      220,
      scrollable: scrollable,
    );
  }

  testWidgets('one vertical scroll reaches all commerce modes and later discovery', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    await reachLowerSections(tester);
    expect(find.byKey(const ValueKey<String>('marketplace-section-Visit to Buy')), findsOneWidget);
    expect(find.text('Services at Shop'), findsOneWidget);
    expect(find.text('New arrivals'), findsOneWidget);
    expect(find.text('Recommended for you'), findsOneWidget);
    expect(find.text('All nearby products and services'), findsOneWidget);
    expect(find.textContaining('Home · Categories'), findsOneWidget,
        reason: 'the shell navigation remains fixed while Home scrolls');

    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  });

  testWidgets('loading Buy Online does not block Visit, Services, or later sections', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    await tester.pumpWidget(host(buyLoading: true));
    await tester.pump();
    await reachLowerSections(tester);

    expect(find.text('Visit to Buy'), findsOneWidget);
    expect(find.text('Services at Shop'), findsOneWidget);
    expect(find.text('Recommended for you'), findsOneWidget);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  });

  testWidgets('empty or failed Buy Online stays compact and leaves later content reachable', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    await tester.pumpWidget(host(buyEmpty: true));
    await tester.pumpAndSettle();
    await reachLowerSections(tester);
    expect(find.text('Services at Shop'), findsOneWidget);
    expect(find.text('All nearby products and services'), findsOneWidget);

    await tester.pumpWidget(host(buyError: true));
    await tester.pump();
    await reachLowerSections(tester);
    expect(find.text('Services at Shop'), findsOneWidget);
    expect(find.text('All nearby products and services'), findsOneWidget);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  });
}

class _PresetModeController extends MarketplaceHomeModeFeedController {
  _PresetModeController({
    required Ref ref,
    required CommerceMode mode,
    required MarketplaceHomeModeFeedState state,
  }) : super(ref: ref, mode: mode, pin: null, shopId: null) {
    this.state = state;
  }
}

class _PresetAllController extends MarketplaceHomeAllFeedController {
  _PresetAllController({required Ref ref, required MarketplaceHomeAllFeedState state})
      : super(ref: ref, pin: null, shopId: null) {
    this.state = state;
  }
}

class _EmptyWishlistController extends WishlistController {
  @override
  Future<List<WishlistItem>> build() async => const [];
}
