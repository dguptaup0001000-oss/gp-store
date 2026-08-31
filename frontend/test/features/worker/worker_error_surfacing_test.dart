import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/api/error_messages.dart';
import 'package:gpstore/core/storage/token_storage.dart';
import 'package:gpstore/features/worker/data/worker_repository.dart';
import 'package:gpstore/features/worker/domain/worker_models.dart';
import 'package:gpstore/features/worker/presentation/worker_login_screen.dart';

import '../../support/test_api_client.dart';

/// The worker app told every failure the same lie.
///
/// A real account, a working connection, and a correct password produced
/// "Could not sign in. Check the connection and try again." - because the
/// screen caught `on ApiException`, and ApiClient does not throw one.
void main() {
  const notLinked =
      'This login is not linked to a worker record. Ask an administrator to '
      'link it.';

  ApiClient clientFor(FakeHttpClientAdapter adapter, TokenStorage storage) {
    final client = ApiClient(tokenStorage: storage);
    client.dio.httpClientAdapter = adapter;
    return client;
  }

  group('what ApiClient actually throws', () {
    setUp(setUpFakeSecureStorage);

    // THE TRAP, PINNED. Two worker screens caught `on ApiException` and both
    // clauses were unreachable: the interceptor returns a DioException that
    // CARRIES an ApiException in its .error field. Reading this test is
    // meant to stop the next person "tidying" the catch back to the version
    // that cannot fire.
    test('a refused request throws DioException, never a bare ApiException',
        () async {
      final adapter = FakeHttpClientAdapter()
        ..on('GET', '/api/worker/me',
            (_) => const FakeResponse({'message': notLinked}, statusCode: 400));
      final storage = TokenStorage(keyPrefix: 'worker_');
      final repository =
          WorkerRepository(apiClient: clientFor(adapter, storage));

      Object? thrown;
      try {
        await repository.me();
      } catch (e) {
        thrown = e;
      }

      expect(thrown, isA<DioException>());
      expect(thrown, isNot(isA<ApiException>()),
          reason: 'if this ever becomes true, `on ApiException` would work - '
              'but until it does, that clause is dead code');
      expect((thrown as DioException).error, isA<ApiException>());
    });

    test("extractErrorMessage recovers the backend's sentence from it",
        () async {
      final adapter = FakeHttpClientAdapter()
        ..on('GET', '/api/worker/me',
            (_) => const FakeResponse({'message': notLinked}, statusCode: 400));
      final storage = TokenStorage(keyPrefix: 'worker_');
      final repository =
          WorkerRepository(apiClient: clientFor(adapter, storage));

      try {
        await repository.me();
        fail('expected a refusal');
      } catch (e) {
        expect(extractErrorMessage(e), notLinked);
        expect(apiStatusOf(e), 400);
        expect(isConnectivityFailure(e), isFalse,
            reason: 'the server answered - retrying will not change its mind');
      }
    });
  });

  group('the sign-in screen shows why', () {
    setUp(setUpFakeSecureStorage);

    testWidgets('an unlinked account is named, not blamed on the connection',
        (tester) async {
      final adapter = FakeHttpClientAdapter()
        ..on(
            'POST',
            '/api/auth/login',
            (_) => const FakeResponse(
                {'token': 'access-abc', 'refreshToken': 'refresh-xyz'}))
        ..on('GET', '/api/worker/me',
            (_) => const FakeResponse({'message': notLinked}, statusCode: 400));

      final storage = TokenStorage(keyPrefix: 'worker_');
      final client = clientFor(adapter, storage);

      await tester.pumpWidget(MaterialApp(
        home: WorkerLoginScreen(
          apiClient: client,
          tokenStorage: storage,
          repository: WorkerRepository(apiClient: client),
          onSignedIn: (_) => fail('sign-in must not be reported as succeeding'),
        ),
      ));

      await tester.enterText(find.byType(TextField).first, 'someone@gmail.com');
      await tester.enterText(find.byType(TextField).last, 'a-password');
      await tester.tap(find.text('SIGN IN'));
      // Explicit pumps rather than pumpAndSettle: while the request is in
      // flight the button holds a CircularProgressIndicator, which never stops
      // scheduling frames, and pumpAndSettle would sit on it.
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));

      expect(find.text(notLinked), findsOneWidget);
      expect(find.textContaining('Check the connection'), findsNothing);
    });

    // THE OTHER HALF OF THE FIX. Removing the connection message everywhere
    // would be its own bug: a worker who really is in a dead spot must still
    // be told that, rather than shown a sentence about permissions.
    testWidgets('a genuinely dead connection still says so', (tester) async {
      final adapter = FakeHttpClientAdapter()
        ..on(
            'POST',
            '/api/auth/login',
            (options) => throw DioException(
                  requestOptions: options,
                  type: DioExceptionType.connectionError,
                ));

      final storage = TokenStorage(keyPrefix: 'worker_');
      final client = clientFor(adapter, storage);

      await tester.pumpWidget(MaterialApp(
        home: WorkerLoginScreen(
          apiClient: client,
          tokenStorage: storage,
          repository: WorkerRepository(apiClient: client),
          onSignedIn: (_) => fail('sign-in must not be reported as succeeding'),
        ),
      ));

      await tester.enterText(find.byType(TextField).first, 'someone@gmail.com');
      await tester.enterText(find.byType(TextField).last, 'a-password');
      await tester.tap(find.text('SIGN IN'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));

      // ApiClient maps a connectionError to its own sentence and hands it
      // over inside the ApiException, so this is the wording that reaches the
      // screen - not error_messages' "you appear to be offline", which only
      // applies when nothing wrapped the failure.
      expect(find.textContaining('Could not reach the server'), findsOneWidget);
    });

    testWidgets('a reason carried in from the gate is shown on arrival',
        (tester) async {
      final adapter = FakeHttpClientAdapter();
      final storage = TokenStorage(keyPrefix: 'worker_');
      final client = clientFor(adapter, storage);

      await tester.pumpWidget(MaterialApp(
        home: WorkerLoginScreen(
          apiClient: client,
          tokenStorage: storage,
          repository: WorkerRepository(apiClient: client),
          onSignedIn: (_) {},
          initialNotice: notLinked,
        ),
      ));
      await tester.pump();

      // Without this a worker whose link was removed lands on a bare login
      // screen, assumes a typo, and retypes the same password.
      expect(find.text(notLinked), findsOneWidget);
    });
  });

  group('what the worker reads', () {
    test('statuses are words, not constants', () {
      expect(humanizeStatus('OUT_FOR_DELIVERY'), 'Out for delivery');
      expect(humanizeStatus('PICKED_UP'), 'Picked up');
      expect(humanizeStatus('DELIVERED'), 'Delivered');
      expect(humanizeStatus(''), '');
      expect(humanizeStatus('_'), '_', reason: 'no crash on a degenerate value');
    });

    // The cash figure is the one number in this app a worker counts into
    // their hand. amountToCollect is a num, so plain interpolation rendered a
    // whole-rupee 450.0 as "450.0".
    test('cash to collect reads as money', () {
      expect(formatRupees(450), '₹450');
      expect(formatRupees(450.0), '₹450');
      expect(formatRupees(450.5), '₹450.50');
      expect(formatRupees(1234.567), '₹1234.57');
      expect(formatRupees(0), '₹0');
    });
  });

  group('models survive a reply that is not shaped as expected', () {
    // An ACCEPTED scan used to throw a TypeError here rather than open the
    // packing list - telling the worker the scan had failed when the server
    // had recorded it.
    test('a nested order in a loosely-typed map still parses', () {
      final json = <String, dynamic>{
        'accepted': true,
        'outcome': 'ACCEPTED',
        'message': 'Recorded.',
        'order': <dynamic, dynamic>{
          'orderId': 7,
          'orderNumber': 'GP125',
          'items': <dynamic>[
            <dynamic, dynamic>{'name': 'Atta', 'quantity': 2},
          ],
        },
      };

      final outcome = ScanOutcome.fromJson(json);
      expect(outcome.accepted, isTrue);
      expect(outcome.order!.orderNumber, 'GP125');
      expect(outcome.order!.items.single.name, 'Atta');
      expect(outcome.order!.items.single.quantity, 2);
    });
  });
}
