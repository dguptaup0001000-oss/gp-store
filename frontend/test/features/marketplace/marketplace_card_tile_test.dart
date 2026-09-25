import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
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
}
