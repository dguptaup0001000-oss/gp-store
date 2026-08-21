import 'package:dio/dio.dart';

import 'api_client.dart';

/// Turns any thrown object into something worth showing a customer.
///
/// TWO RULES.
///
/// First, a raw exception never reaches the screen. "DioException
/// [connection error]" tells a shopper nothing they can act on and tells an
/// attacker something about the stack.
///
/// Second - and this is what changed - the message has to DISTINGUISH. Every
/// non-backend failure previously collapsed into "Something went wrong.
/// Please try again.", including being offline, which is both the most common
/// failure in an Indian grocery app and the one with a completely different
/// remedy. Telling someone on a train to "try again" is useless advice;
/// telling them they appear to be offline is actionable.
///
/// The backend's own message is still preferred whenever there is one: it
/// knows things this layer cannot, like which coupon expired or which item
/// went out of stock.
String extractErrorMessage(Object error) {
  // The backend spoke. It knows more than we do - use its words.
  if (error is ApiException) return error.message;
  if (error is DioException && error.error is ApiException) {
    return (error.error as ApiException).message;
  }

  if (error is DioException) return _describeDioFailure(error);

  return _unknown;
}

const _unknown = 'Something went wrong. Please try again.';

String _describeDioFailure(DioException error) {
  switch (error.type) {
    case DioExceptionType.connectionError:
      // No route to the server at all. On a phone this is almost always the
      // phone, not the server.
      return 'You appear to be offline. Check your connection and try again.';

    case DioExceptionType.connectionTimeout:
    case DioExceptionType.sendTimeout:
    case DioExceptionType.receiveTimeout:
      // Reached something, but it did not answer in time. Deliberately worded
      // as slowness rather than failure, because a retry genuinely often
      // works - especially against a backend that was cold-starting.
      return 'The connection is slow right now. Please try again in a moment.';

    case DioExceptionType.badCertificate:
      // Worth its own message: this is the one failure a customer should not
      // simply retry, because it can mean the connection is being intercepted.
      return 'The secure connection could not be verified. Please try again on a trusted network.';

    case DioExceptionType.cancel:
      // The app cancelled it - a superseded search, or a screen closed. There
      // is no failure to report.
      return '';

    case DioExceptionType.badResponse:
      return _describeStatus(error.response?.statusCode);

    case DioExceptionType.unknown:
      return _unknown;
  }
}

String _describeStatus(int? status) {
  if (status == null) return _unknown;

  if (status == 401 || status == 403) {
    return 'Your session has expired. Please sign in again.';
  }
  if (status == 404) {
    return 'That is no longer available.';
  }
  if (status == 409) {
    // Stock ran out, or the same action was already taken.
    return 'That could not be completed - it may have changed. Please refresh and try again.';
  }
  if (status == 429) {
    return 'Too many attempts. Please wait a moment and try again.';
  }
  if (status == 503 || status == 502 || status == 504) {
    // The distinction that matters to a customer: it is not their phone, and
    // it is not permanent.
    return 'The store is busy right now. Please try again in a moment.';
  }
  if (status >= 500) {
    return 'Something went wrong at our end. Please try again.';
  }
  return _unknown;
}
