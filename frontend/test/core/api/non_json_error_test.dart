import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/api/error_messages.dart';
import 'package:gpstore/core/storage/token_storage.dart';

/// What the admin is told when the response did not come from this backend.
///
/// Every error this API returns is a JSON object carrying "message". Anything
/// else with a status code came from something in FRONT of it - a hosting
/// platform's HTML error page while the service is down, restarting, or
/// failing its health check.
///
/// That case used to produce "Something went wrong. Please try again.", which
/// is indistinguishable from a validation failure, a permission problem and a
/// dead server. An admin staring at it cannot tell whether it is their input,
/// their phone, or the server - and neither could anyone helping them. It cost
/// an evening of guessing at a bug that had already been fixed and deployed,
/// because the app could not say "the server answered 502".
void main() {
  setUp(() {
    TestWidgetsFlutterBinding.ensureInitialized();
    const channel = MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async => null);
  });

  ApiClient clientAnswering(int status, String body, {String contentType = 'text/html'}) {
    final client = ApiClient(tokenStorage: TokenStorage());
    client.dio.httpClientAdapter = _StaticAdapter(status, body, contentType);
    return client;
  }

  test('a 502 HTML page names the status instead of hiding it', () async {
    final client = clientAnswering(502, '<html><body>Bad Gateway</body></html>');

    try {
      await client.dio.post('/api/products', data: {'name': 'dhoop bati'});
      fail('expected the request to throw');
    } catch (e) {
      final message = extractErrorMessage(e);
      expect(message, contains('502'),
          reason: 'the admin must be able to tell a dead server from bad input; was: $message');
      expect(message.toLowerCase(), contains('server'),
          reason: 'and it must say the server is at fault, not them; was: $message');
    }
  });

  test('a 503 reads as temporary, so retrying is the obvious next step', () async {
    final client = clientAnswering(503, 'Service Unavailable');

    try {
      await client.dio.post('/api/products', data: {'name': 'x'});
      fail('expected the request to throw');
    } catch (e) {
      final message = extractErrorMessage(e);
      expect(message, contains('503'));
      expect(message.toLowerCase(), anyOf(contains('restart'), contains('try again')));
    }
  });

  test('a real backend JSON error still wins - its words beat any of ours', () async {
    final client = clientAnswering(
      400,
      jsonEncode({'status': 400, 'message': 'A required field was missing.'}),
      contentType: 'application/json',
    );

    try {
      await client.dio.post('/api/products', data: {'name': 'x'});
      fail('expected the request to throw');
    } catch (e) {
      // The server knows which field and why; this layer must never talk over it.
      expect(extractErrorMessage(e), 'A required field was missing.');
    }
  });
}

class _StaticAdapter implements HttpClientAdapter {
  _StaticAdapter(this.status, this.body, this.contentType);

  final int status;
  final String body;
  final String contentType;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async =>
      ResponseBody.fromString(body, status, headers: {
        Headers.contentTypeHeader: [contentType],
      });

  @override
  void close({bool force = false}) {}
}
