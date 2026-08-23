import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/worker/domain/worker_models.dart';

/// The worker app's parsing and its one genuinely load-bearing distinction.
void main() {
  group('ScanOutcome', () {
    test('a queued scan is never reported as accepted', () {
      // THE RULE FROM THE BRIEF. A scan that did not reach the server has not
      // happened, and telling a worker otherwise would have them walk away
      // from a carton nobody is accountable for. `queued` and `accepted` are
      // separate fields precisely so this cannot be blurred by a truthiness
      // check somewhere in the UI.
      const outcome = ScanOutcome.offline;

      expect(outcome.accepted, isFalse);
      expect(outcome.queued, isTrue);
      expect(outcome.message,
          'Connection unavailable. Scan will be submitted when connection returns.');
    });

    test('the server\'s own sentence is what reaches the worker', () {
      // "Order already assigned to Rahul" is worth ten times a generic
      // failure: it tells the worker who to hand the carton to. Nothing in the
      // app may replace it with wording of its own.
      final outcome = ScanOutcome.fromJson({
        'accepted': false,
        'outcome': 'ALREADY_SCANNED',
        'message': 'Order already assigned to Rahul.',
        'orderNumber': 'GP125',
      });

      expect(outcome.accepted, isFalse);
      expect(outcome.outcome, 'ALREADY_SCANNED');
      expect(outcome.message, 'Order already assigned to Rahul.');
      expect(outcome.orderNumber, 'GP125');
      expect(outcome.queued, isFalse, reason: 'a refusal is not a queued scan');
    });

    test('a replayed scan still counts as accepted', () {
      // A retry that the server answered from its record is a success for the
      // worker - the order IS theirs. Reporting it as a failure would send
      // them looking for a problem that does not exist.
      final outcome = ScanOutcome.fromJson({
        'accepted': true,
        'outcome': 'ACCEPTED',
        'message': 'Order GP125 is yours.',
        'replayed': true,
      });

      expect(outcome.accepted, isTrue);
      expect(outcome.replayed, isTrue);
    });

    test('missing fields do not crash the screen a worker is standing in front of', () {
      final outcome = ScanOutcome.fromJson({});
      expect(outcome.accepted, isFalse);
      expect(outcome.outcome, 'UNKNOWN');
      expect(outcome.orderNumber, isNull);
    });
  });

  group('WorkerProfile', () {
    test('a worker with no territory parses cleanly', () {
      // Real state, not an error: territories are drawn by an administrator
      // and a worker can exist before one covers them. The home screen says
      // so rather than showing a blank where a subzone should be.
      final profile = WorkerProfile.fromJson({
        'workerCode': 'D21',
        'name': 'Rahul',
        'status': 'AVAILABLE',
        'todaysOrders': 8,
      });

      expect(profile.workerCode, 'D21');
      expect(profile.subzoneCode, isNull);
      expect(profile.zoneCode, isNull);
      expect(profile.todaysOrders, 8);
    });

    test('todaysOrders survives a JSON number of either shape', () {
      // Jackson serialises a long as a bare number; a count that arrives as
      // 8.0 from any layer in between must not throw on a phone.
      expect(WorkerProfile.fromJson({'todaysOrders': 8}).todaysOrders, 8);
      expect(WorkerProfile.fromJson({'todaysOrders': 8.0}).todaysOrders, 8);
      expect(WorkerProfile.fromJson({}).todaysOrders, 0);
    });
  });

  group('WorkerScanRow', () {
    test('refused rows are readable, because they are shown too', () {
      final row = WorkerScanRow.fromJson({
        'orderNumber': 'GP125',
        'outcome': 'NOT_AUTHORISED',
        'reason': 'This order is in Z7B, which belongs to Rahul.',
        'subzoneCode': 'Z7B',
        'scannedAt': '2026-08-23T12:42:18',
      });

      expect(row.accepted, isFalse);
      expect(row.reason, contains('Rahul'));
      expect(row.scannedAt?.hour, 12);
      expect(row.scannedAt?.minute, 42);
    });

    test('an unparseable timestamp is null rather than an exception', () {
      final row = WorkerScanRow.fromJson({'outcome': 'ACCEPTED', 'scannedAt': 'not a date'});
      expect(row.scannedAt, isNull);
      expect(row.accepted, isTrue);
    });
  });
}
