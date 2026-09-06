import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/api/error_messages.dart';

void main() {
  group('meansShopViewIsStale', () {
    test('409 means something changed under the customer', () {
      expect(
        meansShopViewIsStale(
            ApiException(statusCode: 409, message: 'Aashirvaad Atta 5 kg is currently unavailable')),
        isTrue,
      );
    });

    test('404 covers a shop that closed as well as an item that went', () {
      // A suspended shop answers 404 exactly like one that never existed,
      // deliberately - so the app cannot tell them apart and does not try.
      expect(meansShopViewIsStale(ApiException(statusCode: 404, message: 'Shop not found')),
          isTrue);
    });

    test('403 on a shop-scoped route means the scope moved', () {
      expect(
        meansShopViewIsStale(
            ApiException(statusCode: 403, message: 'This account is not associated with a shop.')),
        isTrue,
      );
    });

    test('401 is a dead session, not a stale view', () {
      // Reloading the screen will not fix it; the refresh path in ApiClient
      // handles it, and if that fails the customer is signed out.
      expect(
        meansShopViewIsStale(ApiException(statusCode: 401, message: 'expired')),
        isFalse,
      );
    });

    test('a network failure is not a stale view either', () {
      expect(meansShopViewIsStale(Exception('offline')), isFalse);
    });

    test('the message shown is still the backend\'s own words', () {
      // This layer decides whether to RELOAD. It never decides what to SAY -
      // the backend knows which item went out of stock and this does not.
      const backendSaid = 'Only 3 of that item can still be returned.';
      expect(
        extractErrorMessage(ApiException(statusCode: 409, message: backendSaid)),
        backendSaid,
      );
    });
  });
}
