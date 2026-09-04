import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/worker/data/worker_repository.dart';

import '../../support/test_api_client.dart';

/// What the worker app sends when a rider says the money arrived.
///
/// WHY THIS IS WORTH A TEST. A delivered order was sitting on the admin screen
/// as "COD PENDING" for GBP-sized amounts of rupees, because nothing in any
/// app had ever called the endpoint that settles it. The endpoint existed and
/// was correctly permissioned; no button reached it. So the thing to pin is
/// not the server's arithmetic - that has its own tests - but that this app
/// actually calls the right route with the right body.
///
/// The split is the part that breaks quietly: the server refuses two amounts
/// that do not add up to the penny, and a rider standing at a door would see
/// a refusal for a sum they can see is right.
void main() {
  setUp(setUpFakeSecureStorage);

  ({FakeHttpClientAdapter adapter, List<Map<String, dynamic>> sent, List<String> paths})
      recording() {
    final sent = <Map<String, dynamic>>[];
    final paths = <String>[];
    final adapter = FakeHttpClientAdapter();
    adapter.on('PUT', '/api/payments/order/42/cod/complete', (options) {
      paths.add(options.path);
      sent.add(Map<String, dynamic>.from(options.data as Map));
      return const FakeResponse({});
    });
    adapter.on('POST', '/api/worker/orders/42/customer-rating', (options) {
      paths.add(options.path);
      sent.add(Map<String, dynamic>.from(options.data as Map));
      return const FakeResponse({});
    });
    return (adapter: adapter, sent: sent, paths: paths);
  }

  test('all cash sends the whole amount as cash and zero as UPI', () async {
    final t = recording();
    final repo = WorkerRepository(apiClient: buildTestApiClient(t.adapter));

    await repo.recordCodCollection(
        orderId: 42, cashAmount: 2563.00, upiAmount: 0);

    expect(t.paths.single, '/api/payments/order/42/cod/complete');
    expect(t.sent.single['cashAmount'], 2563.00);
    // ZERO, NOT OMITTED. A missing field reads as "not recorded" on the
    // server, which is a different fact from "none of it was cash".
    expect(t.sent.single['upiAmount'], 0);
  });

  test('a QR payment sends zero cash', () async {
    final t = recording();
    final repo = WorkerRepository(apiClient: buildTestApiClient(t.adapter));

    await repo.recordCodCollection(
        orderId: 42, cashAmount: 0, upiAmount: 2563.00);

    expect(t.sent.single['cashAmount'], 0);
    expect(t.sent.single['upiAmount'], 2563.00);
  });

  test('a split still adds up to the amount due, to the paisa', () async {
    final t = recording();
    final repo = WorkerRepository(apiClient: buildTestApiClient(t.adapter));

    // THE CASE THAT WOULD FAIL SILENTLY IF THE SCREEN DID THIS IN RUPEES.
    // 2563.00 - 1500.00 in doubles is not reliably 1063.00, and the server
    // refuses a split that does not reconcile exactly.
    const duePaise = 256300;
    const cashPaise = 150000;
    await repo.recordCodCollection(
      orderId: 42,
      cashAmount: cashPaise / 100,
      upiAmount: (duePaise - cashPaise) / 100,
    );

    final body = t.sent.single;
    final cash = body['cashAmount'] as num;
    final upi = body['upiAmount'] as num;
    expect(((cash + upi) * 100).round(), duePaise,
        reason: 'a split that does not add up is refused at the door');
    expect(cash, 1500.00);
    expect(upi, 1063.00);
  });

  test('a rating posts the score and nothing about who gave it', () async {
    final t = recording();
    final repo = WorkerRepository(apiClient: buildTestApiClient(t.adapter));

    await repo.rateCustomer(orderId: 42, score: 7);

    expect(t.paths.single, '/api/worker/orders/42/customer-rating');
    expect(t.sent.single['score'], 7);
    // The rider and the customer are both derived from the token and the
    // order on the server. A field the client fills in is a field somebody
    // can forge.
    expect(t.sent.single.containsKey('partnerId'), isFalse);
    expect(t.sent.single.containsKey('customerId'), isFalse);
  });
}
