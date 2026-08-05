import 'dart:async';

import 'package:dio/dio.dart';

import '../storage/token_storage.dart';

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

/// The base URL is set at build/run time via --dart-define, NOT hardcoded -
/// same principle as the backend's env-var-driven config. Point this at your
/// real deployed API (Render URL) or a local backend during dev.
///
/// Example dev run:
///   flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8081/v1
/// (10.0.2.2 is the special alias the Android emulator uses for your
/// computer's own localhost - NOT a typo. Use your machine's real LAN IP
/// instead if testing on a physical device.)
const _defaultBaseUrl = 'http://10.0.2.2:8081/v1';

class ApiClient {
  ApiClient({required this.tokenStorage, this.onSessionExpired}) {
    _dio = Dio(
      BaseOptions(
        baseUrl: const String.fromEnvironment('API_BASE_URL', defaultValue: _defaultBaseUrl),
        connectTimeout: const Duration(seconds: 15),
        receiveTimeout: const Duration(seconds: 15),
      ),
    );

    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: _attachAccessToken,
        onError: _handleError,
      ),
    );
  }

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

    if (response?.statusCode == 401 && error.requestOptions.path != '/api/auth/refresh') {
      final newAccessToken = await _refreshAccessToken();

      if (newAccessToken != null) {
        // Retry the original request once, with the new token.
        final retryOptions = error.requestOptions;
        retryOptions.headers['Authorization'] = 'Bearer $newAccessToken';
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
    }

    handler.next(_mapToApiException(error));
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
    String message = 'Something went wrong. Please try again.';
    Map<String, String>? fieldErrors;
    bool gotMessageFromBody = false;

    if (data is Map<String, dynamic>) {
      if (data['message'] is String) {
        message = data['message'] as String;
        gotMessageFromBody = true;
      }
      if (data['fieldErrors'] is Map) {
        fieldErrors = (data['fieldErrors'] as Map).map(
          (key, value) => MapEntry(key.toString(), value.toString()),
        );
      }
    }

    // Only fall back to a generic/derived message when the response body
    // itself didn't actually give us one - a JSON body that IS a Map but has
    // no "message" key (e.g. Spring Boot's default unhandled-exception
    // shape: {timestamp, status, error, path}) must NOT be treated as "got a
    // real message", or it silently swallows the status-code detail below.
    if (!gotMessageFromBody) {
      if (error.type == DioExceptionType.connectionTimeout ||
          error.type == DioExceptionType.receiveTimeout) {
        message = 'Connection timed out. Check your internet and try again.';
      } else if (error.type == DioExceptionType.connectionError) {
        message = 'Could not reach the server. Check your internet connection.';
      } else {
        // TEMPORARY, for active debugging - see the matching comment in
        // root_screen.dart. Including the raw status code (when there is
        // one) turns "Something went wrong" into an actual clue instead of
        // a dead end - safe to remove once the underlying cause is found.
        final statusCode = error.response?.statusCode;
        if (statusCode != null) {
          message = 'Something went wrong (HTTP $statusCode). Please try again.';
        }
      }
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
