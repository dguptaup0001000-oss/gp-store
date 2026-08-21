import 'dart:math';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/retry_policy.dart';

DioException _failure({
  String method = 'GET',
  int? status,
  DioExceptionType type = DioExceptionType.badResponse,
  Map<String, List<String>>? headers,
}) {
  final options = RequestOptions(path: '/api/products', method: method);
  return DioException(
    requestOptions: options,
    type: type,
    response: status == null
        ? null
        : Response(
            requestOptions: options,
            statusCode: status,
            headers: Headers.fromMap(headers ?? const {}),
          ),
  );
}

void main() {
  const policy = RetryPolicy();

  group('what may be retried', () {
    test('a GET that hit a temporary server fault is retried', () {
      for (final status in [429, 502, 503, 504]) {
        expect(policy.shouldRetry(_failure(status: status), 0), isTrue,
            reason: '$status is the server saying "not now"');
      }
    });

    test('a GET that lost the connection is retried', () {
      for (final type in [
        DioExceptionType.connectionTimeout,
        DioExceptionType.receiveTimeout,
        DioExceptionType.sendTimeout,
        DioExceptionType.connectionError,
      ]) {
        expect(policy.shouldRetry(_failure(type: type), 0), isTrue);
      }
    });

    test('a 500 is NOT retried', () {
      // A 500 is a bug in a code path; the same request hits the same bug.
      // Retrying only multiplies the error and delays the customer seeing it.
      expect(policy.shouldRetry(_failure(status: 500), 0), isFalse);
    });

    test('ordinary client errors are NOT retried', () {
      for (final status in [400, 401, 403, 404, 409, 422]) {
        expect(policy.shouldRetry(_failure(status: status), 0), isFalse);
      }
    });

    test('a cancelled request is NOT retried', () {
      // The screen was disposed. Nobody is waiting for the answer.
      expect(policy.shouldRetry(_failure(type: DioExceptionType.cancel), 0), isFalse);
    });
  });

  group('the rule that protects orders', () {
    test('a POST is never retried, whatever went wrong', () {
      // THE most important assertion in this file. A POST that timed out has
      // an unknown outcome - it may have been committed with the response
      // lost on the way back. Repeating it is how one tap becomes two orders.
      for (final status in [429, 502, 503, 504]) {
        expect(policy.shouldRetry(_failure(method: 'POST', status: status), 0), isFalse,
            reason: 'POST must not be retried on $status');
      }
      for (final type in [
        DioExceptionType.connectionTimeout,
        DioExceptionType.receiveTimeout,
        DioExceptionType.sendTimeout,
        DioExceptionType.connectionError,
      ]) {
        expect(policy.shouldRetry(_failure(method: 'POST', type: type), 0), isFalse,
            reason: 'POST must not be retried on $type');
      }
    });

    test('no other mutating method is retried either', () {
      for (final method in ['PUT', 'PATCH', 'DELETE']) {
        expect(policy.shouldRetry(_failure(method: method, status: 503), 0), isFalse,
            reason: '$method changes server state');
      }
    });

    test('lowercase method names are still recognised as mutating', () {
      // Dio does not normalise the method string, so a repository written as
      // dio.request(method: 'post') must not slip past the guard.
      expect(policy.shouldRetry(_failure(method: 'post', status: 503), 0), isFalse);
    });
  });

  group('the attempt budget', () {
    test('stops after maxAttempts', () {
      expect(policy.shouldRetry(_failure(status: 503), 0), isTrue);
      expect(policy.shouldRetry(_failure(status: 503), 1), isTrue);
      expect(policy.shouldRetry(_failure(status: 503), 2), isFalse,
          reason: 'two retries means three requests total, and then it stops');
    });

    test('a smaller budget is honoured', () {
      const strict = RetryPolicy(maxAttempts: 1);
      expect(strict.shouldRetry(_failure(status: 503), 0), isTrue);
      expect(strict.shouldRetry(_failure(status: 503), 1), isFalse);
    });
  });

  group('backoff', () {
    test('grows with each attempt', () {
      // Compared at the ceiling, since the delay itself is jittered.
      const noJitter = _MaxRandom();
      final first = policy.delayFor(1, random: noJitter);
      final second = policy.delayFor(2, random: noJitter);
      expect(second, greaterThan(first));
    });

    test('is capped, so a long outage does not produce a minute-long wait', () {
      const noJitter = _MaxRandom();
      expect(policy.delayFor(10, random: noJitter), lessThanOrEqualTo(policy.maxDelay));
    });

    test('is jittered, so clients that failed together do not return together', () {
      // Without jitter, every client that failed at the same instant retries
      // at the same instant and hits the recovering server as one wall.
      final delays = <int>{};
      for (var i = 0; i < 40; i++) {
        delays.add(policy.delayFor(2).inMilliseconds);
      }
      expect(delays.length, greaterThan(1), reason: 'delays are identical - jitter is not applied');
    });

    test('never negative', () {
      for (var attempt = 1; attempt <= 6; attempt++) {
        expect(policy.delayFor(attempt).inMilliseconds, greaterThanOrEqualTo(0));
      }
    });

    test('honours Retry-After when the server states one', () {
      final error = _failure(status: 429, headers: {'retry-after': ['2']});
      expect(policy.delayFor(1, error: error), const Duration(seconds: 2));
    });

    test('a Retry-After beyond the cap is clamped, not obeyed literally', () {
      // A server asking for 300 seconds should not freeze the screen for five
      // minutes; the request fails and the customer can act.
      final error = _failure(status: 429, headers: {'retry-after': ['300']});
      expect(policy.delayFor(1, error: error), policy.maxDelay);
    });

    test('an unparseable Retry-After falls back to computed backoff', () {
      // The HTTP-date form is legal but rare; misreading it would produce a
      // wildly wrong wait, so the safer failure is to ignore it.
      final error = _failure(status: 429, headers: {'retry-after': ['Wed, 21 Oct 2026 07:28:00 GMT']});
      expect(policy.delayFor(1, error: error).inMilliseconds,
          lessThanOrEqualTo(policy.maxDelay.inMilliseconds));
    });
  });
}

/// Returns the largest value in range, so a jittered delay collapses to its
/// ceiling and can be compared deterministically.
class _MaxRandom implements Random {
  const _MaxRandom();

  @override
  int nextInt(int max) => max - 1;

  @override
  bool nextBool() => true;

  @override
  double nextDouble() => 1.0;
}
