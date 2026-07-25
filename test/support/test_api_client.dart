import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/storage/token_storage.dart';

/// flutter_secure_storage talks to a platform channel that doesn't exist in
/// a plain `flutter_test` environment - without this, ANY TokenStorage read
/// (which ApiClient's interceptor calls on every request) would throw
/// MissingPluginException. This stubs the channel to behave like "nothing
/// stored yet", which is exactly what a fresh test run should simulate.
void setUpFakeSecureStorage() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
    channel,
    (MethodCall call) async {
      if (call.method == 'read') return null;
      if (call.method == 'readAll') return <String, String>{};
      return null;
    },
  );
}

class FakeResponse {
  const FakeResponse(this.body, {this.statusCode = 200});
  final dynamic body; // a Map or List - the decoded JSON shape
  final int statusCode;
}

/// A minimal fake HttpClientAdapter - maps "METHOD path" to a canned JSON
/// response, so a repository can be tested against real request/response
/// parsing without hitting a real network or backend.
class FakeHttpClientAdapter implements HttpClientAdapter {
  final Map<String, FakeResponse Function(RequestOptions)> _handlers = {};

  void on(String method, String path, FakeResponse Function(RequestOptions options) handler) {
    _handlers['${method.toUpperCase()} $path'] = handler;
  }

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final key = '${options.method.toUpperCase()} ${options.path}';
    final handler = _handlers[key];

    if (handler == null) {
      throw DioException(
        requestOptions: options,
        error: 'No fake handler registered for $key',
      );
    }

    final response = handler(options);
    return ResponseBody.fromString(
      jsonEncode(response.body),
      response.statusCode,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

/// Builds a real ApiClient wired to a fake adapter - use this in repository
/// tests instead of hitting a real backend.
ApiClient buildTestApiClient(FakeHttpClientAdapter adapter) {
  final client = ApiClient(tokenStorage: TokenStorage());
  client.dio.httpClientAdapter = adapter;
  return client;
}
