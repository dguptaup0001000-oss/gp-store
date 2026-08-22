import 'dart:async';

import 'package:dio/dio.dart';

import '../config/app_environment.dart';
import '../storage/token_storage.dart';
import 'retry_policy.dart';

/// Matches the backend's ApiError shape exactly (see GlobalExceptionHandler /
/// ApiError.java) so error messages shown to the user are the real backend
/// message, not a generic "something went wrong".
class ApiException implements Exception {
  ApiException({required this.statusCode, required this.message, this.fieldErrors});

  final int? statusCode;
  final String message;
  final Map<String, String>? fieldErrors;

  @override
  String toString() => message;
}

class ApiClient {
  ApiClient({
    required this.tokenStorage,
    this.onSessionExpired,
    AppEnvironment? environment,
    RetryPolicy retryPolicy = const RetryPolicy(),
  })  : environment = environment ?? AppEnvironment.current,
        _retryPolicy = retryPolicy {
    final env = this.environment;

    _dio = Dio(
      BaseOptions(
        // One named environment decides the host - see AppEnvironment for why
        // production still points at Render rather than at Oracle.
        baseUrl: env.baseUrl,
        connectTimeout: env.timeout,
        receiveTimeout: env.timeout,
        // sendTimeout was missing entirely. Without it an upload that stalls
        // mid-body - a phone dropping to one bar while an admin posts a
        // product image - hangs with no ceiling at all, because connect and
        // receive timeouts do not cover the sending phase.
        sendTimeout: env.timeout,
      ),
    );

    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: _attachAccessToken,
        onError: _handleError,
      ),
    );
  }

  /// Which deployment this client talks to.
  final AppEnvironment environment;

  final RetryPolicy _retryPolicy;

  /// Counts retries per request. Keyed on the RequestOptions instance, which
  /// is the same object across a retry chain, so a request cannot escape its
  /// own attempt budget by looking like a new one.
  static const _attemptKey = 'gpstore_retry_attempt';
  // Marks a request that has already been retried once after a token
  // refresh, so a persistently-401 endpoint cannot refresh in a loop.
  static const _refreshedKey = 'gpstore_refreshed_once';

  late final Dio _dio;
  final TokenStorage tokenStorage;

  /// Called when the refresh token itself is invalid/expired - the app
  /// should navigate to the login screen when this fires.
  final void Function()? onSessionExpired;

  // Guards against multiple simultaneous 401s all trying to refresh at once
  // (e.g. several API calls in flight when the access token expires) -
  // only the first one actually calls /refresh; the rest wait for it.
  Completer<String?>? _refreshCompleter;

  Dio get dio => _dio;

  Future<void> _attachAccessToken(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    // Auth endpoints carry their own credential (password, or the refresh
    // token itself) - never attach a possibly-stale access token to them.
    final isAuthEndpoint = options.path.startsWith('/api/auth/');
    if (!isAuthEndpoint) {
      final token = await tokenStorage.getAccessToken();
      if (token != null) {
        options.headers['Authorization'] = 'Bearer $token';
      }
    }
    handler.next(options);
  }

  Future<void> _handleError(DioException error, ErrorInterceptorHandler handler) async {
    final response = error.response;

    // The refresh-once guard is load-bearing, not defensive tidying. The
    // retry below goes back through this same interceptor, so a request that
    // keeps answering 401 would refresh, retry, 401, refresh again - with no
    // attempt counter of its own, unlike _retryIfSafe - and rotate the
    // refresh token on every pass. That is not hypothetical: the backend
    // answered 401 to every genuine 403 until an accessDeniedHandler was
    // added (see SecurityConfig), so any customer touching an admin-only
    // route landed in exactly this loop.
    //
    // Fixing the backend closes the case that was actually happening. This
    // bounds the class: any endpoint that keeps returning 401 after a
    // successful refresh - a revoked session, a role change mid-session - is
    // a dead session, not a stale token, and one refresh is enough to know it.
    final alreadyRefreshed = error.requestOptions.extra[_refreshedKey] == true;

    if (response?.statusCode == 401 &&
        error.requestOptions.path != '/api/auth/refresh' &&
        !alreadyRefreshed) {
      final newAccessToken = await _refreshAccessToken();

      if (newAccessToken != null) {
        // Retry the original request once, with the new token.
        final retryOptions = error.requestOptions;
        retryOptions.headers['Authorization'] = 'Bearer $newAccessToken';
        retryOptions.extra[_refreshedKey] = true;
        try {
          final retryResponse = await _dio.fetch(retryOptions);
          handler.resolve(retryResponse);
          return;
        } on DioException catch (retryError) {
          handler.next(retryError);
          return;
        }
      } else {
        await tokenStorage.clear();
        onSessionExpired?.call();
      }

      // A 401 that could not be refreshed is a dead session, not a transient
      // fault - fall through to the error rather than retrying it.
      handler.next(_mapToApiException(error));
      return;
    }

    if (await _retryIfSafe(error, handler)) return;

    handler.next(_mapToApiException(error));
  }

  /// Retries the request if the policy allows, and reports whether it did.
  ///
  /// The attempt count rides on the RequestOptions' own extra map, so it
  /// survives into the retried request and a failing endpoint cannot loop
  /// forever by presenting each attempt as a fresh one.
  Future<bool> _retryIfSafe(DioException error, ErrorInterceptorHandler handler) async {
    final options = error.requestOptions;
    final attempt = (options.extra[_attemptKey] as int? ?? 0) + 1;

    if (!_retryPolicy.shouldRetry(error, attempt - 1)) return false;

    await Future<void>.delayed(_retryPolicy.delayFor(attempt, error: error));

    options.extra[_attemptKey] = attempt;
    try {
      handler.resolve(await _dio.fetch(options));
    } on DioException catch (retryError) {
      // Back through this same interceptor, so a second failure is judged by
      // the same policy - and stops once the budget is spent.
      handler.next(_mapToApiException(retryError));
    }
    return true;
  }

  Future<String?> _refreshAccessToken() async {
    // If a refresh is already in flight, wait for it instead of starting a
    // second one - prevents a burst of concurrent 401s from each rotating
    // the refresh token and invalidating each other (see backend
    // RefreshTokenService - rotation means only ONE refresh token is valid
    // at a time).
    if (_refreshCompleter != null) {
      return _refreshCompleter!.future;
    }

    final completer = Completer<String?>();
    _refreshCompleter = completer;

    try {
      final refreshToken = await tokenStorage.getRefreshToken();
      if (refreshToken == null) {
        completer.complete(null);
        return null;
      }

      final response = await _dio.post(
        '/api/auth/refresh',
        data: {'refreshToken': refreshToken},
      );

      final newAccessToken = response.data['token'] as String;
      final newRefreshToken = response.data['refreshToken'] as String;

      await tokenStorage.saveTokens(
        accessToken: newAccessToken,
        refreshToken: newRefreshToken,
      );

      completer.complete(newAccessToken);
      return newAccessToken;
    } catch (_) {
      completer.complete(null);
      return null;
    } finally {
      _refreshCompleter = null;
    }
  }

  DioException _mapToApiException(DioException error) {
    final data = error.response?.data;
    // The intermittent "couldn't load account" failure this used to surface
    // raw DioExceptionType detail for is fixed (see SecurityConfig's
    // AuthenticationEntryPoint) - a bare 403 with no body from Spring
    // Security's default entry point, which this branch never matched, so
    // it always fell through to this fallback. Back to a plain message now
    // that the real cause is known and fixed at the source.
    String message = 'Something went wrong. Please try again.';
    Map<String, String>? fieldErrors;

    if (data is Map<String, dynamic>) {
      if (data['message'] is String) {
        message = data['message'] as String;
      }
      if (data['fieldErrors'] is Map) {
        fieldErrors = (data['fieldErrors'] as Map).map(
          (key, value) => MapEntry(key.toString(), value.toString()),
        );
      }
    } else if (error.type == DioExceptionType.connectionTimeout ||
        error.type == DioExceptionType.receiveTimeout) {
      message = 'Connection timed out. Check your internet and try again.';
    } else if (error.type == DioExceptionType.connectionError) {
      message = 'Could not reach the server. Check your internet connection.';
    }

    return error.copyWith(
      error: ApiException(
        statusCode: error.response?.statusCode,
        message: message,
        fieldErrors: fieldErrors,
      ),
    );
  }
}
