import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_feed_models.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_offer.dart';

MarketplaceOffer offer({
  String mode = 'ONLINE_PURCHASE',
  String priceMode = 'EXACT_PRICE',
  bool addable = true,
  double? price = 250,
  double? priceMax,
  String? availability,
  int? minutes,
  String? addressLine = '12 Market Road',
  String? locality = 'Bajaj Nagar',
  String? city = 'Nagpur',
  String? pincode = '440010',
  double? lat = 21.1,
  double? lng = 79.2,
  double? distanceKm,
  int shopId = 7,
}) {
  return MarketplaceOffer.fromJson({
    'productId': 1,
    'productName': 'Haircut',
    'productVariantId': 5,
    'price': price,
    'priceMax': priceMax,
    'priceMode': priceMode,
    'commerceMode': mode,
    'addable': addable,
    'offlineAvailability': availability,
    'serviceDurationMinutes': minutes,
    'shopId': shopId,
    'shopName': 'Sharma Salon',
    'addressLine': addressLine,
    'locality': locality,
    'city': city,
    'pincode': pincode,
    'shopLatitude': lat,
    'shopLongitude': lng,
    'distanceKm': distanceKm,
  });
}

void main() {
  group('Where to go', () {
    test('the address reads as one line', () {
      expect(offer().whereToGo, '12 Market Road, Bajaj Nagar, Nagpur, 440010');
    });

    test('a shop that filled in nothing gets no address line, not a placeholder', () {
      final nothing = offer(
          addressLine: null, locality: null, city: null, pincode: null);
      expect(nothing.whereToGo, isNull,
          reason: '"Address not available" under a Visit to Buy heading reads '
              'as an app failure; no line at all reads as a shop that has not said');
    });

    test('a partly filled address skips the blanks rather than showing commas', () {
      expect(offer(addressLine: null, pincode: null).whereToGo,
          'Bajaj Nagar, Nagpur');
    });

    test('directions need coordinates, and say so by being absent', () {
      expect(offer().canBeVisited, isTrue);
      expect(offer(lat: null, lng: null).canBeVisited, isFalse);
    });
  });

  group('What the price says', () {
    test('an exact price is a number', () {
      expect(offer().priceLabel(), '₹250');
    });

    test('a starting-from price never pretends to be committed', () {
      expect(offer(priceMode: 'STARTING_FROM').priceLabel(), 'From ₹250');
    });

    test('a range shows both ends', () {
      expect(offer(priceMode: 'PRICE_RANGE', priceMax: 900).priceLabel(),
          '₹250 – ₹900');
    });

    test('a range with no top falls back to the one number it has', () {
      expect(offer(priceMode: 'PRICE_RANGE').priceLabel(), '₹250');
    });

    test('ask at shop is the answer when there is no number to give', () {
      expect(offer(priceMode: 'ASK_AT_SHOP').priceLabel(), 'Ask at shop');
      expect(offer(price: null).priceLabel(), 'Ask at shop');
    });
  });

  group('What the shop said about it', () {
    test('availability reads as a sentence, not a constant', () {
      expect(offer(availability: 'MADE_TO_ORDER').availabilityLabel,
          'Made to order');
      expect(offer(availability: 'OUT_OF_STOCK').availabilityLabel,
          'Out of stock right now');
    });

    test('an availability this build has never heard of draws nothing', () {
      expect(offer(availability: 'SOMETHING_NEW').availabilityLabel, isNull,
          reason: 'a newer backend during a rollout must not put a raw enum '
              'name on a customer screen');
      expect(offer(availability: null).availabilityLabel, isNull);
    });

    test('a duration is read in hours once it passes one', () {
      expect(offer(minutes: 30).durationLabel, 'About 30 min');
      expect(offer(minutes: 60).durationLabel, 'About 1 hr');
      expect(offer(minutes: 95).durationLabel, 'About 1 hr 35 min');
      expect(offer(minutes: null).durationLabel, isNull);
      expect(offer(minutes: 0).durationLabel, isNull);
    });

    test('distance is metres up close and kilometres beyond', () {
      expect(offer(distanceKm: 0.35).distanceLabel, '350 m away');
      expect(offer(distanceKm: 2.46).distanceLabel, '2.5 km away');
      expect(offer(distanceKm: null).distanceLabel, isNull);
    });
  });

  group('Grouping the offers', () {
    test('each mode lands under its own heading and none is dropped', () {
      final offers = ProductOffers([
        offer(mode: 'ONLINE_PURCHASE', shopId: 1),
        offer(mode: 'VISIT_TO_BUY', addable: false, shopId: 2),
        offer(mode: 'SERVICE_AT_SHOP', addable: false, shopId: 3),
      ]);

      expect(offers.deliverable.length, 1);
      expect(offers.visitable.length, 1);
      expect(offers.services.length, 1);
      expect(offers.all.length, 3,
          reason: 'hiding a shop that delivers from somebody on a '
              'Visit-to-Buy card answers a narrower question than they asked');
    });

    test('the shop count counts shops, not listings', () {
      final offers = ProductOffers([
        offer(shopId: 1),
        offer(shopId: 1, mode: 'VISIT_TO_BUY', addable: false),
        offer(shopId: 2),
      ]);
      expect(offers.shopCount, 2);
    });

    test('nothing nearby is an empty answer, not an error', () {
      expect(const ProductOffers([]).isEmpty, isTrue);
      expect(const ProductOffers([]).shopCount, 0);
    });
  });

  group('Talking to a newer server', () {
    test('an unknown commerce mode cannot silently become Buy Online', () {
      expect(() => offer(mode: 'TELEPORT'), throwsFormatException);
      expect(offer(priceMode: 'HAGGLE').priceMode, ListingPriceMode.exact);
    });

    test('addable is the server\'s answer and is never inferred', () {
      final refused = offer(mode: 'ONLINE_PURCHASE', addable: false);
      expect(refused.addable, isFalse,
          reason: 'a screen deciding for itself would eventually disagree with '
              'the backend, and it would disagree by offering to buy something '
              'that cannot be bought');
    });
  });
}
