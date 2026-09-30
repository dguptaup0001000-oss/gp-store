import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/images/gp_network_image.dart';
import 'package:gpstore/core/util/app_haptics.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_feed_models.dart';
import 'package:gpstore/features/marketplace/presentation/marketplace_card_tile.dart';
import 'package:gpstore/features/wishlist/domain/wishlist_models.dart';
import 'package:gpstore/features/wishlist/presentation/wishlist_providers.dart';

class _NoNetworkWishlistController extends WishlistController {
  @override
  Future<List<WishlistItem>> build() async => const [];

  @override
  Future<bool?> toggle(int productId) async => true;
}

void main() {
  setUp(() {
    AppHaptics.resetForTest();
    AppHaptics.enabled = false;
  });

  tearDown(() => AppHaptics.enabled = true);

  testWidgets('marketplace card passes its real product image to the shared image widget',
      (tester) async {
    const imageUrl = 'https://images.example.test/tata-namak.jpg';
    final card = MarketplaceCard.fromJson(const {
      'productId': 51,
      'name': 'TATA NAMAK',
      'commerceMode': 'ONLINE_PURCHASE',
      'addable': true,
      'priceMode': 'EXACT',
      'imageUrl': imageUrl,
    });

    await tester.pumpWidget(ProviderScope(
      overrides: [
        wishlistControllerProvider.overrideWith(_NoNetworkWishlistController.new),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 220,
            height: 330,
            child: MarketplaceCardTile(card: card),
          ),
        ),
      ),
    ));
    await tester.pump();

    final image = tester.widget<GpNetworkImage>(find.byType(GpNetworkImage));
    expect(card.imageUrl, imageUrl);
    expect(image.url, imageUrl);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Visit to Buy action is tappable and never exposes cart purchase',
      (tester) async {
    var opened = false;
    final card = MarketplaceCard.fromJson(const {
      'productId': 5,
      'name': 'Visit fixture',
      'commerceMode': 'VISIT_TO_BUY',
      'addable': false,
      'priceMode': 'STARTING_FROM',
      'inStock': true,
    });

    await tester.pumpWidget(ProviderScope(
      overrides: [
        wishlistControllerProvider.overrideWith(_NoNetworkWishlistController.new),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 220,
            height: 330,
            child: MarketplaceCardTile(card: card, onTap: () => opened = true),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('VISIT SHOP'), findsOneWidget);
    expect(find.text('ADD'), findsNothing);
    await tester.tap(find.text('VISIT SHOP'));
    await tester.pumpAndSettle();
    expect(opened, isTrue);
  });

  testWidgets('tapping the product image gives exactly one haptic and opens it',
      (tester) async {
    var opened = false;
    const card = MarketplaceCard(
      productId: 9,
      name: 'Soap',
      commerceMode: CommerceMode.buyOnline,
      priceMode: ListingPriceMode.exact,
      addable: true,
      sellingPrice: 40,
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        wishlistControllerProvider.overrideWith(_NoNetworkWishlistController.new),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 116,
            height: 230,
            child: MarketplaceCardTile(
              card: card,
              compact: true,
              onTap: () => opened = true,
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.byType(GpNetworkImage));
    await tester.pump();

    expect(opened, isTrue);
    expect(AppHaptics.callCount, 1);
    expect(tester.takeException(), isNull,
        reason: 'a three-across phone card must not overflow');
  });
}
