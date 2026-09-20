import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/selling_mode.dart';

void main() {
  group('How a shop says it sells something', () {
    test('an online item may only be priced exactly', () {
      expect(PriceMode.allowedFor(SellingMode.onlinePurchase),
          [PriceMode.exact],
          reason: 'a cart totals this number and a receipt prints it, so '
              '"from Rs 500" cannot be what the customer is charged');

      final wrong = const SellingSetup(
        selling: SellingMode.onlinePurchase,
        price: PriceMode.startingFrom,
      ).problem();
      expect(wrong, contains('exact price'));
    });

    test('an offline listing may be priced any of the four ways', () {
      expect(PriceMode.allowedFor(SellingMode.visitToBuy).length, 4);
      expect(PriceMode.allowedFor(SellingMode.serviceAtShop).length, 4);
      expect(
          const SellingSetup(
            selling: SellingMode.visitToBuy,
            price: PriceMode.askAtShop,
          ).problem(),
          isNull);
    });

    test('a range needs a top, and the top cannot sit under the bottom', () {
      expect(
          const SellingSetup(
            selling: SellingMode.visitToBuy,
            price: PriceMode.range,
          ).problem(),
          contains('top price'));

      expect(
          const SellingSetup(
            selling: SellingMode.visitToBuy,
            price: PriceMode.range,
            priceMax: 100,
          ).problem(sellingPrice: 400),
          contains('below the starting price'));

      expect(
          const SellingSetup(
            selling: SellingMode.visitToBuy,
            price: PriceMode.range,
            priceMax: 900,
          ).problem(sellingPrice: 400),
          isNull);
    });

    test('a service takes a plausible amount of time', () {
      expect(
          const SellingSetup(
            selling: SellingMode.serviceAtShop,
            price: PriceMode.startingFrom,
            serviceMinutes: 0,
          ).problem(),
          isNotNull);
      expect(
          const SellingSetup(
            selling: SellingMode.serviceAtShop,
            price: PriceMode.startingFrom,
            serviceMinutes: 60 * 24 * 8,
          ).problem(),
          isNotNull);
      expect(
          const SellingSetup(
            selling: SellingMode.serviceAtShop,
            price: PriceMode.startingFrom,
            serviceMinutes: 30,
          ).problem(),
          isNull);
    });
  });

  group('What gets sent', () {
    test('a product carries no service duration', () {
      final json = const SellingSetup(
        selling: SellingMode.visitToBuy,
        price: PriceMode.startingFrom,
        serviceMinutes: 45,
      ).toJson();

      expect(json['serviceDurationMinutes'], isNull,
          reason: 'a leftover duration on a product is a stale field the '
              'customer would be shown');
      expect(json['commerceMode'], 'VISIT_TO_BUY');
    });

    test('an online item carries no shelf availability', () {
      final json = const SellingSetup(
        selling: SellingMode.onlinePurchase,
        price: PriceMode.exact,
        stock: OfflineStock.madeToOrder,
      ).toJson();

      expect(json['offlineAvailability'], isNull);
      expect(json['priceMax'], isNull);
    });

    test('an offline listing with nothing said reads as available', () {
      final json = const SellingSetup(
        selling: SellingMode.visitToBuy,
        price: PriceMode.askAtShop,
      ).toJson();

      expect(json['offlineAvailability'], 'AVAILABLE');
    });

    test('a range sends its top, other price modes do not', () {
      expect(
          const SellingSetup(
            selling: SellingMode.visitToBuy,
            price: PriceMode.range,
            priceMax: 900,
          ).toJson()['priceMax'],
          900);
      expect(
          const SellingSetup(
            selling: SellingMode.visitToBuy,
            price: PriceMode.startingFrom,
            priceMax: 900,
          ).toJson()['priceMax'],
          isNull);
    });
  });

  group('Talking to a newer server', () {
    test('an unknown mode reads as the world this app already knew', () {
      expect(SellingMode.fromWire('SOMETHING_NEW'), SellingMode.onlinePurchase);
      expect(SellingMode.fromWire(null), SellingMode.onlinePurchase);
      expect(PriceMode.fromWire('WHAT'), PriceMode.exact);
      expect(OfflineStock.fromWire(null), OfflineStock.available);
    });

    test('every known mode round-trips through its wire value', () {
      for (final mode in SellingMode.values) {
        expect(SellingMode.fromWire(mode.wire), mode);
      }
      for (final mode in PriceMode.values) {
        expect(PriceMode.fromWire(mode.wire), mode);
      }
      for (final stock in OfflineStock.values) {
        expect(OfflineStock.fromWire(stock.wire), stock);
      }
    });
  });
}
