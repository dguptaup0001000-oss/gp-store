import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/storage/token_storage.dart';

/// A request that keeps answering 401 must refresh ONCE, not forever.
///
/// _handleError retries through _dio.fetch, which goes back through this same
/// interceptor. Without a guard, a persistently-401 endpoint refreshes, retries,
/// gets 401, refreshes again - and unlike _retryIfSafe that branch carried no
/// attempt counter, so it did not terminate. Every pass also rotates the
/// refresh token, because the backend rotates on each refresh.
///
/// This was not hypothetical. The backend answered 401 to every genuine 403
/// until an accessDeniedHandler was added (see SecurityConfig and
/// AccessDeniedStatusTest), so any customer whose app touched an admin-only
/// route - a stale role, a demoted admin - drove exactly this loop.
void main() {
  /// Secure storage that actually holds tokens, unlike the shared
  /// setUpFakeSecureStorage helper which reports "nothing stored yet" - a
  /// refresh cannot even be attempted without a refresh token on disk.
  void useStorageHoldingTokens() {
    TestWidgetsFlutterBinding.ensureInitialized();
    final store = <String, String>{
      'access_token': 'stale-access-token',
      'refresh_token': 'valid-refresh-token',
    };
    const channel =
        MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
      final args = (call.arguments as Map?)?.cast<String, dynamic>() ?? {};
      switch (call.method) {
        case 'read':
          return store[args['key'] as String];
        case 'readAll':
          return Map<String, String>.from(store);
        case 'write':
          store[args['key'] as String] = args['value'] as String;
          return null;
        case 'delete':
          store.remove(args['key'] as String);
          return null;
        case 'deleteAll':
          store.clear();
          return null;
      }
      return null;
    });
  }

  /// Counts what each path was asked for, and always refuses the protected
  /// one - the shape of a 403 masked as 401, or of a revoked session.
  late int protectedCalls;
  late int refreshCalls;

  HttpClientAdapter countingAdapter() => _CountingAdapter(
        onProtected: () => protectedCalls++,
        onRefresh: () => refreshCalls++,
      );

  setUp(() {
    protectedCalls = 0;
    refreshCalls = 0;
    useStorageHoldingTokens();
  });

  test('a persistently-401 endpoint refreshes once and then gives up',
      () async {
    final client = ApiClient(tokenStorage: TokenStorage());
    client.dio.httpClientAdapter = countingAdapter();

    await expectLater(
      client.dio.get('/api/customers'),
      throwsA(isA<Exception>()),
    );

    expect(refreshCalls, 1,
        reason:
            'the token must be refreshed exactly once, not on every retry - '
            'each refresh rotates the refresh token on the backend');
    expect(protectedCalls, 2,
        reason:
            'the original request plus exactly one retry; more than that is the loop');
  });

  test('the session is not wiped when the refresh itself succeeded', () async {
    var sessionExpiredCalls = 0;
    final client = ApiClient(
      tokenStorage: TokenStorage(),
      onSessionExpired: () => sessionExpiredCalls++,
    );
    client.dio.httpClientAdapter = countingAdapter();

    await expectLater(
        client.dio.get('/api/customers'), throwsA(isA<Exception>()));

    // A refusal that survives a successful refresh is a permission problem,
    // not a dead session - signing the customer out here would be wrong.
    expect(sessionExpiredCalls, 0,
        reason: 'onSessionExpired is for an unusable refresh token, not for a '
            'request the server keeps refusing');
  });

  test('a malformed refresh body does not wipe the stored session', () async {
    var sessionExpiredCalls = 0;
    final client = ApiClient(
      tokenStorage: TokenStorage(),
      onSessionExpired: () => sessionExpiredCalls++,
    );
    client.dio.httpClientAdapter = _HtmlRefreshAdapter();

    await expectLater(
        client.dio.get('/api/customers'), throwsA(isA<Exception>()));
    expect(sessionExpiredCalls, 0);
    expect(await TokenStorage().getRefreshToken(), 'valid-refresh-token');
  });
}

class _HtmlRefreshAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path == '/api/auth/refresh') {
      return ResponseBody.fromString(
        '<html>bad gateway</html>',
        200,
        headers: {
          Headers.contentTypeHeader: ['text/html'],
        },
      );
    }
    return ResponseBody.fromString(
      jsonEncode({'message': 'Authentication required'}),
      401,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

class _CountingAdapter implements HttpClientAdapter {
  _CountingAdapter({required this.onProtected, required this.onRefresh});

  final void Function() onProtected;
  final void Function() onRefresh;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path == '/api/auth/refresh') {
      onRefresh();
      return ResponseBody.fromString(
        jsonEncode({
          'token': 'fresh-access-token',
          'refreshToken': 'rotated-refresh-token'
        }),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
    }

    onProtected();
    return ResponseBody.fromString(
      jsonEncode({
        'status': 401,
        'error': 'Unauthorized',
        'message': 'Authentication required',
        'path': '/error',
      }),
      401,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
