import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/storage/token_storage.dart';

/// A file upload must survive the token refresh that happens under it.
///
/// THE BUG THIS PINS. _handleError retries the original request through
/// _dio.fetch(requestOptions), reusing the very same body object. For JSON
/// that is harmless - a Map can be serialised twice. A multipart body cannot:
/// dio's MultipartFile throws StateError('already been finalized') the second
/// time it is asked for its bytes.
///
/// So a catalogue import whose access token expired between the tap and the
/// upload did not retry - it failed with an error about finalisation, and the
/// shopkeeper saw a bulk import die for no reason they could act on. The
/// window is not small either: these uploads are megabytes over a shop's
/// mobile connection, which is exactly when a token has time to expire.
void main() {
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

  setUp(useStorageHoldingTokens);

  test('a multipart upload is retried with its bytes intact after a refresh',
      () async {
    final adapter = _RefuseOnceAdapter();
    final client = ApiClient(tokenStorage: TokenStorage());
    client.dio.httpClientAdapter = adapter;

    final form = FormData.fromMap({
      'file': MultipartFile.fromBytes(
        utf8.encode('SKU,Selling Price\nRICE-1KG,58\n'),
        filename: 'prices.csv',
      ),
      'mode': 'UPDATE_ONLY',
    });

    final response = await client.dio
        .post('/api/admin/catalog/import/preview', data: form);

    expect(response.statusCode, 200);
    expect(adapter.uploadAttempts, 2,
        reason: 'the original refused request plus one retry after refresh');

    // THE POINT. The retry must carry the file, not an empty or half-consumed
    // body - a retry that uploads nothing would "succeed" against a lenient
    // server while importing an empty catalogue.
    expect(adapter.lastBody, contains('RICE-1KG,58'));
    expect(adapter.lastBody, contains('filename="prices.csv"'));
    expect(adapter.lastBody, contains('UPDATE_ONLY'));
  });
}

/// Refuses the first upload with 401, accepts the second, and remembers what
/// was actually sent each time.
class _RefuseOnceAdapter implements HttpClientAdapter {
  int uploadAttempts = 0;
  String lastBody = '';

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path == '/api/auth/refresh') {
      return ResponseBody.fromString(
        jsonEncode({
          'token': 'fresh-access-token',
          'refreshToken': 'rotated-refresh-token',
        }),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
    }

    uploadAttempts++;
    // Drain the body the way a real server would. This is also what makes the
    // MultipartFile finalised, so a retry that reuses it throws.
    final bytes = <int>[];
    if (requestStream != null) {
      await for (final chunk in requestStream) {
        bytes.addAll(chunk);
      }
    }
    lastBody = utf8.decode(bytes, allowMalformed: true);

    if (uploadAttempts == 1) {
      return ResponseBody.fromString(
        jsonEncode({'message': 'Authentication required'}),
        401,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
    }
    return ResponseBody.fromString(
      jsonEncode({'runId': 7, 'totalRows': 1, 'validRows': 1}),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
