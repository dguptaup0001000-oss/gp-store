import 'dart:math';

import 'package:dio/dio.dart';

/// Decides whether a failed request may be sent again, and how long to wait.
///
/// THE RULE THAT MATTERS: only GET is ever retried.
///
/// A retry is safe exactly when repeating the request cannot change anything
/// on the server. That is true of GET by definition and is not true of the
/// requests this app cares most about - placing an order, starting a payment.
/// A POST that times out has an outcome nobody knows: the request may have
/// been received and committed with the response lost on the way back.
/// Sending it again in that state is how one tap becomes two orders.
///
/// Order creation does carry an Idempotency-Key, and the backend does
/// fingerprint it, so in principle those POSTs are safe to repeat. They are
/// still not retried here, because that safety depends on a guarantee living
/// in another system, and a client-side retry loop is the wrong place to bet
/// on it. If the server did not receive the request, the customer taps again;
/// if it did, they get the order. Both outcomes are correct, and neither
/// requires this layer to guess.
class RetryPolicy {
  const RetryPolicy({
    this.maxAttempts = 2,
    this.baseDelay = const Duration(milliseconds: 400),
    this.maxDelay = const Duration(seconds: 4),
  });

  /// Retries AFTER the first try, so 2 means at most three requests total.
  /// Deliberately small: a client that keeps retrying a struggling backend is
  /// adding load to the thing it is waiting for, and 1,000 clients doing it
  /// together is how a slow backend becomes a dead one.
  final int maxAttempts;

  final Duration baseDelay;
  final Duration maxDelay;

  /// Statuses worth trying again. Each is the server saying "not now" rather
  /// than "no":
  ///   429 - rate limited, explicitly temporary
  ///   502/503/504 - proxy could not reach the app, or it was too slow
  ///
  /// 500 is deliberately ABSENT. A 500 is a bug in a code path, and the same
  /// request will hit the same bug; retrying only multiplies the error and
  /// delays the customer seeing a message.
  static const _retriableStatuses = {429, 502, 503, 504};

  bool shouldRetry(DioException error, int attempt) {
    if (attempt >= maxAttempts) return false;

    // The single most important line in this class.
    final method = error.requestOptions.method.toUpperCase();
    if (method != 'GET') return false;

    final status = error.response?.statusCode;
    if (status != null) return _retriableStatuses.contains(status);

    // No response at all: a timeout, a dropped connection, a refused socket.
    // Worth one more try on a GET - this is what a train tunnel looks like.
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.connectionError:
        return true;
      case DioExceptionType.cancel:
        // The screen was disposed or the user moved on. Retrying a cancelled
        // request re-does work nobody is waiting for.
        return false;
      default:
        return false;
    }
  }

  /// How long to wait before attempt [attempt] (1-based).
  ///
  /// Exponential, capped, with jitter. The jitter is not decoration: without
  /// it every client that failed at the same moment - which is what happens
  /// when a backend briefly falls over - retries at the same moment too, and
  /// the recovering server is hit by a synchronised wall of traffic. Spreading
  /// them out is the difference between a backend recovering and a backend
  /// being knocked down again by its own clients.
  Duration delayFor(int attempt, {DioException? error, Random? random}) {
    // A 429 may carry Retry-After, which is the server stating a number
    // rather than this client guessing one. Honour it.
    final retryAfter = _retryAfterOf(error);
    if (retryAfter != null) return retryAfter > maxDelay ? maxDelay : retryAfter;

    final exponential = baseDelay * pow(2, attempt - 1).toDouble();
    final capped = exponential > maxDelay ? maxDelay : exponential;

    // Full jitter: anywhere between zero and the capped delay.
    final rng = random ?? Random();
    return Duration(milliseconds: rng.nextInt(capped.inMilliseconds + 1));
  }

  Duration? _retryAfterOf(DioException? error) {
    final header = error?.response?.headers.value('retry-after');
    if (header == null) return null;

    // Only the delta-seconds form is handled. The HTTP-date form is legal but
    // rare, and misreading it would produce a wildly wrong wait - falling back
    // to the computed backoff is the safer failure.
    final seconds = int.tryParse(header.trim());
    if (seconds == null || seconds < 0) return null;
    return Duration(seconds: seconds);
  }
}
