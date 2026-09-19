import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/error_messages.dart';
import 'package:gpstore/features/admin/data/platform_repository.dart';

import '../../support/test_api_client.dart';

/// Two things a merchant could not do, and what the app now sends.
///
/// BOTH WERE FOUND ON A REAL DEVICE, and neither was a broken request - they
/// were a missing route and a missing recipient list. So these assert the
/// address on the envelope and the words on the screen, which is where both
/// faults actually lived.
void main() {
  setUpAll(setUpFakeSecureStorage);

  group('a business with no shop can be given one', () {
    test('the console posts to the merchant, not to the shops list', () async {
      final adapter = FakeHttpClientAdapter();
      String? calledPath;
      Map<String, dynamic>? sentBody;

      adapter.on('POST', '/api/platform/merchants/2/first-shop', (options) {
        calledPath = options.path;
        sentBody = options.data as Map<String, dynamic>;
        return const FakeResponse({
          'merchantId': 2,
          'businessName': 'GUPT SAREE',
          'shopId': 11,
          'shopCode': 'gupt-saree',
          'shopName': 'GUPT SAREE',
          'ownerCustomerId': 993,
          'merchantStatusBefore': 'APPLICATION',
          'merchantStatusAfter': 'APPROVED',
        });
      });

      final repository =
          PlatformRepository(apiClient: buildTestApiClient(adapter));
      final made = await repository.addFirstShop(
        merchantId: 2,
        displayName: 'GUPT SAREE',
        latitude: 26.76,
        longitude: 83.37,
        maxDeliveryRadiusKm: 8,
      );

      expect(calledPath, '/api/platform/merchants/2/first-shop',
          reason: 'POST /api/platform/shops refuses a business that is not '
              'already approved, which is exactly where this one is stuck');
      // The two fields ShopReadiness calls blocking. A shop without them looks
      // finished and can never sell.
      expect(sentBody!['latitude'], 26.76);
      expect(sentBody!['longitude'], 83.37);
      expect(sentBody!['maxDeliveryRadiusKm'], 8);

      expect(made.shopId, 11);
      expect(made.merchantStatusBefore, 'APPLICATION');
      expect(made.merchantStatusAfter, 'APPROVED',
          reason: 'the console has to be able to say the business moved');
    });

    test('a zero-shop sign-in is explained, not reported as a refusal', () {
      // THE SCREEN A REAL MERCHANT SAW, over a Retry that could never help and
      // a Sign out that changed nothing.
      final error = DioException(
        requestOptions: RequestOptions(path: '/api/customers/me'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/customers/me'),
          statusCode: 403,
          data: const {
            'status': 403,
            'message': 'This account is not associated with a shop.',
          },
        ),
        type: DioExceptionType.badResponse,
      );

      expect(meansNoShopYet(error), isTrue);
      final message = extractErrorMessage(error);
      expect(message, contains('No shop has been set up'));
      expect(message, contains('your account itself is fine'.split(' ').last));
      expect(message, isNot(contains('not associated')),
          reason: 'the backend wording reads as a refusal, and this is not one');
    });

    test('being refused THIS shop still reads as a refusal', () {
      // One word apart, and a completely different situation: this account
      // does have shops and reached for one that is not theirs.
      final error = DioException(
        requestOptions: RequestOptions(path: '/api/shop/listings'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/shop/listings'),
          statusCode: 403,
          data: const {
            'status': 403,
            'message': 'This account is not associated with this shop.',
          },
        ),
        type: DioExceptionType.badResponse,
      );

      expect(meansNoShopYet(error), isFalse,
          reason: 'mistaking a genuine cross-shop refusal for "nothing set up '
              'yet" would tell a merchant to ring support about a wall that is '
              'doing its job');
      // It falls through to the ordinary 403 wording, which is a refusal and
      // reads as one. (A raw DioException carries no ApiException, so the
      // backend's own sentence is not used here - only the status is.)
      expect(extractErrorMessage(error), 'You do not have permission to do that.');
      expect(extractErrorMessage(error), isNot(contains('No shop has been set up')));
    });

    test('a 404 is never mistaken for a missing shop', () {
      final error = DioException(
        requestOptions: RequestOptions(path: '/api/customers/me'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/customers/me'),
          statusCode: 404,
          data: const {
            'status': 404,
            'message': 'This account is not associated with a shop.',
          },
        ),
        type: DioExceptionType.badResponse,
      );
      expect(meansNoShopYet(error), isFalse,
          reason: 'the status is half the signal');
    });
  });
}
