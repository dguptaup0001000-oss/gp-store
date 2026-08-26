import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/api/error_messages.dart';

DioException _dio(DioExceptionType type, {int? status}) {
  final options = RequestOptions(path: '/api/products');
  return DioException(
    requestOptions: options,
    type: type,
    response: status == null
        ? null
        : Response(requestOptions: options, statusCode: status),
  );
}

void main() {
  group('what the customer is told', () {
    test('the backend message wins whenever there is one', () {
      // The server knows which coupon expired or which item sold out; this
      // layer does not.
      final wrapped = DioException(
        requestOptions: RequestOptions(path: '/api/orders'),
        error:
            ApiException(statusCode: 400, message: 'Coupon SAVE20 has expired'),
      );
      expect(extractErrorMessage(wrapped), 'Coupon SAVE20 has expired');
      expect(
          extractErrorMessage(
              ApiException(statusCode: 400, message: 'Out of stock')),
          'Out of stock');
    });

    test('being offline says so, rather than "something went wrong"', () {
      // The most common failure in this app, and the one with a completely
      // different remedy. "Try again" is useless advice on a train.
      final message =
          extractErrorMessage(_dio(DioExceptionType.connectionError));
      expect(message.toLowerCase(), contains('offline'));
    });

    test('a timeout reads as slowness, not failure', () {
      // A retry genuinely often works here, especially against a backend that
      // was cold-starting, so the wording should invite one.
      for (final type in [
        DioExceptionType.connectionTimeout,
        DioExceptionType.sendTimeout,
        DioExceptionType.receiveTimeout,
      ]) {
        expect(extractErrorMessage(_dio(type)).toLowerCase(), contains('slow'));
      }
    });

    test('an expired session says to sign in, not to try again', () {
      expect(
        extractErrorMessage(_dio(DioExceptionType.badResponse, status: 401))
            .toLowerCase(),
        contains('sign in'),
      );
    });

    test('a 403 is a permission problem, not a sign-in prompt', () {
      final message =
          extractErrorMessage(_dio(DioExceptionType.badResponse, status: 403));
      expect(message.toLowerCase(), contains('permission'));
      expect(message.toLowerCase(), isNot(contains('sign in')));
    });

    test('a busy store is distinguished from the customer\'s own connection',
        () {
      // It is not their phone and it is not permanent - both worth saying.
      for (final status in [502, 503, 504]) {
        final message = extractErrorMessage(
            _dio(DioExceptionType.badResponse, status: status));
        expect(message.toLowerCase(), contains('busy'));
        expect(message.toLowerCase(), isNot(contains('offline')));
      }
    });

    test('rate limiting asks the customer to wait', () {
      expect(
        extractErrorMessage(_dio(DioExceptionType.badResponse, status: 429))
            .toLowerCase(),
        contains('wait'),
      );
    });

    test('a bad certificate is not framed as a retry', () {
      // This is the one failure a customer should not simply retry - it can
      // mean the connection is being intercepted.
      final message = extractErrorMessage(_dio(DioExceptionType.badCertificate))
          .toLowerCase();
      expect(message, contains('secure'));
    });

    test('a cancelled request produces no message at all', () {
      // The app cancelled it - a superseded search, or a closed screen. There
      // is no failure to report, and reporting one turns ordinary typing into
      // a screen full of errors.
      expect(extractErrorMessage(_dio(DioExceptionType.cancel)), isEmpty);
    });
  });

  group('what the customer is never told', () {
    test('no raw exception text ever reaches the screen', () {
      // Both useless to a shopper and mildly informative to an attacker.
      final leaky = [
        extractErrorMessage(_dio(DioExceptionType.connectionError)),
        extractErrorMessage(_dio(DioExceptionType.unknown)),
        extractErrorMessage(_dio(DioExceptionType.badResponse, status: 500)),
        extractErrorMessage(
            Exception('PostgresException: relation does not exist')),
        extractErrorMessage(
            StateError('null check operator used on a null value')),
      ];
      for (final message in leaky) {
        expect(message, isNot(contains('DioException')));
        expect(message, isNot(contains('Exception')));
        expect(message, isNot(contains('Postgres')));
        expect(message, isNot(contains('null check')));
      }
    });

    test('an unrecognised error still gets a usable sentence', () {
      expect(extractErrorMessage(StateError('boom')),
          'Something went wrong. Please try again.');
      expect(extractErrorMessage('a bare string'),
          'Something went wrong. Please try again.');
    });

    test('every message reads as a sentence, not a code', () {
      final messages = [
        extractErrorMessage(_dio(DioExceptionType.connectionError)),
        extractErrorMessage(_dio(DioExceptionType.receiveTimeout)),
        extractErrorMessage(_dio(DioExceptionType.badResponse, status: 401)),
        extractErrorMessage(_dio(DioExceptionType.badResponse, status: 503)),
        extractErrorMessage(_dio(DioExceptionType.badResponse, status: 500)),
      ];
      for (final message in messages) {
        expect(message, endsWith('.'));
        expect(message.length, greaterThan(15));
      }
    });
  });
}
