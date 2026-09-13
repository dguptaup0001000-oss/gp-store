import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/api/error_messages.dart';

import '../../support/test_api_client.dart';

/// The app has to recognise ONE refusal out of every 403 the backend sends.
///
/// An account the platform opened is still on a one-time password, and until
/// it is replaced the backend refuses every route but the change itself -
/// including /api/customers/me, the one call the app makes to find out who it
/// is signed in as. So this refusal is not an error to display: it is a
/// redirect, and the app has to tell it apart from the ordinary "you do not
/// have permission to do that" 403, which means very nearly the opposite.
void main() {
  setUpAll(setUpFakeSecureStorage);

  /// What ApiClient's interceptor actually throws.
  ///
  /// A DioException CARRYING an ApiException, not an ApiException. Two worker
  /// screens once had dead `on ApiException` clauses for exactly this reason,
  /// so each helper is checked through the wrapper as well as bare.
  DioException wrapped(ApiException inner) {
    return DioException(
      requestOptions: RequestOptions(path: '/api/customers/me'),
      response: Response(
        requestOptions: RequestOptions(path: '/api/customers/me'),
        statusCode: inner.statusCode,
      ),
      error: inner,
    );
  }

  /// Makes the real request, through the real interceptor, against a canned
  /// 403 - so this covers the plumbing and not just the predicate.
  Future<Object> refusalFrom(Map<String, dynamic> body) async {
    final adapter = FakeHttpClientAdapter()
      ..on('GET', '/api/customers/me',
          (_) => FakeResponse(body, statusCode: 403));
    final client = buildTestApiClient(adapter);
    try {
      await client.dio.get('/api/customers/me');
      fail('a 403 must not come back as a success');
    } catch (error) {
      return error;
    }
  }

  group('the code survives the trip from the wire to a decision', () {
    test('a body carrying "code" reaches the decision, message intact',
        () async {
      final refusal = await refusalFrom(const {
        'status': 403,
        'error': 'Forbidden',
        'code': 'PASSWORD_CHANGE_REQUIRED',
        'message': 'Set your own password before using the app.',
      });

      expect(apiErrorCodeOf(refusal), passwordChangeRequiredCode);
      expect(meansPasswordChangeRequired(refusal), isTrue);
      // The backend's own words still reach the screen - the code is a
      // routing signal, not a replacement for what it said.
      expect(extractErrorMessage(refusal),
          'Set your own password before using the app.');
    });

    test('the exact body JwtFilter writes is understood', () async {
      // BYTE FOR BYTE what rejectUntilPasswordChanged() sends. The cases
      // above prove the predicate; this proves it against the string the
      // server actually produces, so a reworded or restructured body cannot
      // quietly stop being recognised.
      //
      // A plain test(), not testWidgets(): the request goes through
      // ApiClient's real interceptor, whose retry path awaits a
      // Future.delayed that never completes inside a widget test's
      // fake-async zone. That cost a hung run to learn.
      final refusal = await refusalFrom(const {
        'status': 403,
        'error': 'Forbidden',
        'code': 'PASSWORD_CHANGE_REQUIRED',
        'message': 'Set your own password before using the app. '
            'This account is still on the one-time password it was created '
            'with.',
      });

      expect(meansPasswordChangeRequired(refusal), isTrue);
    });

    test('a 403 with no code leaves it null rather than guessing', () async {
      final refusal = await refusalFrom(
          const {'message': 'You do not have permission to do that.'});

      expect(apiErrorCodeOf(refusal), isNull);
      expect(meansPasswordChangeRequired(refusal), isFalse);
    });
  });

  group('meansPasswordChangeRequired', () {
    test('403 plus the code is the one case that matches', () {
      final inner = ApiException(
          statusCode: 403,
          message: 'Set your own password before using the app.',
          code: passwordChangeRequiredCode);

      expect(meansPasswordChangeRequired(inner), isTrue);
      expect(meansPasswordChangeRequired(wrapped(inner)), isTrue,
          reason: 'the interceptor wraps it, so the wrapper must match too');
    });

    test('an ordinary permission refusal does NOT match', () {
      // THE CASE THAT WOULD BREAK THE CONSOLE IF IT DID. Every shop-scope
      // refusal and every route an ADMIN may not reach answers 403. Reading
      // those as "you owe a password change" would hijack the whole app and
      // ask a merchant to replace a password that is already their own.
      expect(
        meansPasswordChangeRequired(ApiException(
            statusCode: 403,
            message: 'This account is not associated with a shop.')),
        isFalse,
      );
    });

    test('the code on any other status does not match', () {
      // Belt and braces against a future endpoint reusing the string in a
      // 400 or 409, where it would mean something else entirely.
      expect(
        meansPasswordChangeRequired(ApiException(
            statusCode: 409,
            message: 'conflict',
            code: passwordChangeRequiredCode)),
        isFalse,
      );
    });

    test('being offline is not a password problem', () {
      expect(meansPasswordChangeRequired(Exception('offline')), isFalse);
      expect(
        meansPasswordChangeRequired(DioException(
          requestOptions: RequestOptions(path: '/api/customers/me'),
          type: DioExceptionType.connectionError,
        )),
        isFalse,
      );
    });
  });
}
