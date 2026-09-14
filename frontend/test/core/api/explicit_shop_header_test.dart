import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/marketplace/shop_context.dart';
import 'package:gpstore/core/storage/token_storage.dart';

import '../../support/test_api_client.dart';

/// Which shop a request names, and who gets to decide.
///
/// THE DEFAULT BELONGS TO THE INTERCEPTOR; AN EXPLICIT CHOICE BELONGS TO THE
/// CALL SITE. The interceptor used to stamp the active shop onto every
/// request unconditionally, which meant no screen could read another shop's
/// shelf without first SWITCHING the whole app to it - so a customer looking
/// at a competitor's prices lost the categories, the feed and the basket
/// pricing of the shop they were actually in.
///
/// THIS GRANTS NOTHING. A shop id on a request may only NARROW a scope the
/// credential already permits: TenantResolver.select refuses a shop the caller
/// is not entitled to rather than honouring it. A call site naming a shop is
/// asking, not deciding, and these tests are about which question gets asked.
void main() {
  setUpAll(setUpFakeSecureStorage);

  /// The headers one request actually went out with.
  Future<Map<String, dynamic>> headersOf({
    required int? activeShop,
    Options? options,
  }) async {
    final client = ApiClient(
      tokenStorage: TokenStorage(),
      activeShopId: () => activeShop,
    );
    late RequestOptions sent;
    client.dio.httpClientAdapter = _Capture((request) => sent = request);
    try {
      await client.dio.get('/api/products/feed', options: options);
    } catch (_) {
      // The adapter answers 200 with an empty body; anything thrown after the
      // request was captured is not what this test is about.
    }
    return sent.headers;
  }

  test('with nothing chosen, no shop is named at all', () async {
    // The shipped single-shop request: the backend answers Shop #1 without
    // being asked, and a header would be a claim this app has no reason to
    // make.
    final headers = await headersOf(activeShop: null);
    expect(headers.containsKey(shopHeaderName), isFalse);
  });

  test('the active shop is supplied when the caller named none', () async {
    final headers = await headersOf(activeShop: 3);
    expect(headers[shopHeaderName], '3');
  });

  test('a caller that named a shop is not overruled', () async {
    // THE ONE THAT MATTERS. Reading shop 6's shelf while acting for shop 3
    // must ask shop 6. Overwriting it here is what forced a switch just to
    // look, and it is exactly the line that used to do so.
    final headers = await headersOf(
      activeShop: 3,
      options: Options(headers: {shopHeaderName: '6'}),
    );
    expect(headers[shopHeaderName], '6');
  });
}

/// Answers every request with an empty 200, and hands the request back first.
class _Capture implements HttpClientAdapter {
  _Capture(this.onRequest);

  final void Function(RequestOptions) onRequest;

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<List<int>>? requestStream,
      Future<void>? cancelFuture) async {
    onRequest(options);
    return ResponseBody.fromString('{}', 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }

  @override
  void close({bool force = false}) {}
}
