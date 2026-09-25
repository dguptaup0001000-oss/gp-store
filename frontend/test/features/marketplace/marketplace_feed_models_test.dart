import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_feed_models.dart';

/// What the app is allowed to decide for itself about a marketplace card,
/// and what it must take from the server.
void main() {
  group('the server decides what can be bought', () {
    test('addable comes from the response, never from the mode', () {
      // THE POINT OF THIS TEST. It would be easy, and wrong, for a card to
      // work out "visit to buy means no ADD" for itself. The moment the
      // backend's rule and the app's copy of it disagree, the app is the one
      // offering to sell something that cannot be sold. So the flag is the
      // server's and the widget reads only the flag.
      final card = MarketplaceCard.fromJson(const {
        'productId': 1,
        'name': 'Gold ring',
        'commerceMode': 'VISIT_TO_BUY',
        'priceMode': 'STARTING_FROM',
        'sellingPrice': 25000,
        'addable': false,
      });

      expect(card.addable, isFalse);
      expect(card.commerceMode, CommerceMode.visitToBuy);
    });

    test('a missing or unknown mode never becomes Buy Online', () {
      Map<String, dynamic> card(String? mode) => {
            'productId': 2,
            'name': 'Something new',
            if (mode != null) 'commerceMode': mode,
            'addable': false,
          };

      expect(() => MarketplaceCard.fromJson(card('RENT_BY_THE_HOUR')),
          throwsFormatException);
      expect(() => MarketplaceCard.fromJson(card(null)),
          throwsFormatException);
    });
  });

  group('prices say what kind of number they are', () {
    MarketplaceCard priced(String mode, {double? price, double? max}) {
      return MarketplaceCard.fromJson({
        'productId': 3,
        'name': 'Thing',
        'commerceMode': 'VISIT_TO_BUY',
        'priceMode': mode,
        'sellingPrice': price,
        'priceMax': max,
        'addable': false,
      });
    }

    test('an exact price is shown as one, and is a promise', () {
      final card = priced('EXACT_PRICE', price: 499);
      expect(card.priceLabel(), '₹499');
      expect(card.priceIsCommitted, isTrue);
    });

    test('a floor is labelled as a floor', () {
      final card = priced('STARTING_FROM', price: 25000);
      expect(card.priceLabel(), 'From ₹25000');
      expect(card.priceIsCommitted, isFalse,
          reason: 'a merchant who published a floor has not agreed to it as '
              'the final price, and a card must not commit them to one');
    });

    test('a range shows both ends', () {
      final card = priced('PRICE_RANGE', price: 40000, max: 60000);
      expect(card.priceLabel(), '₹40000 – ₹60000');
      expect(card.priceIsCommitted, isFalse);
    });

    test('ask at shop shows no figure at all', () {
      expect(priced('ASK_AT_SHOP').priceLabel(), 'Ask at shop');
    });

    test('a price mode that needs a number and has none degrades honestly', () {
      // Better to say "ask at shop" than to draw a blank or a zero. A zero
      // on a gold ring is not a rendering glitch to the customer reading it.
      expect(priced('STARTING_FROM').priceLabel(), 'Ask at shop');
      expect(priced('PRICE_RANGE').priceLabel(), 'Ask at shop');
    });
  });

  test('feed JSON carries the image URL and keeps the commerce mode in card identity', () {
    final online = MarketplaceCard.fromJson(const {
      'productId': 41,
      'name': 'Tata Salt',
      'commerceMode': 'ONLINE_PURCHASE',
      'imageUrl': 'https://images.example.test/tata-salt.jpg',
      'inStock': true,
      'addable': true,
    });
    final visit = MarketplaceCard.fromJson(const {
      'productId': 41,
      'name': 'Tata Salt',
      'commerceMode': 'VISIT_TO_BUY',
      'addable': false,
    });

    expect(online.imageUrl, 'https://images.example.test/tata-salt.jpg');
    expect(online.inStock, isTrue);
    expect(online.feedKey, isNot(visit.feedKey));
  });
}
