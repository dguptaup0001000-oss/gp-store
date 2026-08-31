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

  group('WorkerOrder', () {
    test('the packing list is what the screen is for', () {
      final order = WorkerOrder.fromJson({
        'orderId': 42,
        'orderNumber': 'GP10245',
        'orderStatus': 'PACKED',
        'deliveryStatus': 'PACKED',
        'deliveryId': 7,
        'allowedNext': ['PICKED_UP', 'CANCELLED'],
        'totalItems': 5,
        'items': [
          {'name': 'Aashirvaad Atta', 'pack': '5 kg', 'quantity': 1},
          {'name': 'Tata Salt', 'pack': '1 kg', 'quantity': 4},
        ],
      });

      expect(order.orderNumber, 'GP10245');
      expect(order.totalItems, 5);
      expect(order.items, hasLength(2));
      expect(order.items.first.pack, '5 kg');
      expect(order.items.last.quantity, 4);
    });

    test('the allowed moves come from the server and nowhere else', () {
      // The screen draws one button per entry here. If the app ever computed
      // this itself, a phone running an old build could offer a transition the
      // server has since removed - and a worker would tap it at a door.
      final order = WorkerOrder.fromJson({
        'orderId': 1,
        'orderNumber': 'GP1',
        'allowedNext': ['OUT_FOR_DELIVERY', 'RETURNED', 'CANCELLED'],
      });

      expect(order.allowedNext, ['OUT_FOR_DELIVERY', 'RETURNED', 'CANCELLED']);
    });

    test('an order with no delivery yet offers no moves at all', () {
      // Packed before anybody was assigned to carry it. The screen shows the
      // packing list and no buttons, which is the truth of that situation
      // rather than a bug.
      final order = WorkerOrder.fromJson({'orderId': 1, 'orderNumber': 'GP1'});

      expect(order.deliveryId, isNull);
      expect(order.allowedNext, isEmpty);
    });

    test('a prepaid order carries no cash to collect', () {
      // The distinction that stops a customer being asked to pay twice: a
      // paid order shows nothing, not its total.
      final prepaid = WorkerOrder.fromJson({
        'orderId': 1,
        'orderNumber': 'GP1',
        'amountToCollect': 0,
        'cashOnDelivery': false,
      });
      expect(prepaid.cashOnDelivery, isFalse);
      expect(prepaid.amountToCollect, 0);

      final cod = WorkerOrder.fromJson({
        'orderId': 2,
        'orderNumber': 'GP2',
        'amountToCollect': 250.5,
        'cashOnDelivery': true,
      });
      expect(cod.cashOnDelivery, isTrue);
      expect(cod.amountToCollect, 250.5);
    });

    test('an order arriving with a scan needs no second request', () {
      // The reason ScanOutcome carries an order at all: SCAN -> SHOW ORDER is
      // one round trip, on the worst connection in the business.
      final outcome = ScanOutcome.fromJson({
        'accepted': true,
        'outcome': 'ACCEPTED',
        'message': 'Order GP10245 is yours.',
        'order': {
          'orderId': 42,
          'orderNumber': 'GP10245',
          'totalItems': 1,
          'items': [
            {'name': 'Tata Salt', 'quantity': 1}
          ],
        },
      });

      expect(outcome.order, isNotNull);
      expect(outcome.order!.orderNumber, 'GP10245');
      expect(outcome.order!.items.single.name, 'Tata Salt');
    });

    test('a refused scan carries no order', () {
      // A refusal must not hand over the contents of the order it just
      // refused to hand over.
      final outcome = ScanOutcome.fromJson({
        'accepted': false,
        'outcome': 'NOT_AUTHORISED',
        'message': 'This order is in Z7B, which belongs to Rahul.',
      });

      expect(outcome.order, isNull);
    });

    test('missing item fields do not crash a screen somebody is standing in front of', () {
      final line = WorkerOrderLine.fromJson({});
      expect(line.name, 'Item');
      expect(line.quantity, 0);
      expect(line.pack, isNull);
    });
  });

  group('WorkerTask', () {
    test('active tasks arrive with the profile, not from their own request', () {
      // One network call for the whole home screen. Two would only mean the
      // slower one arriving later - the screen cannot draw without both.
      final profile = WorkerProfile.fromJson({
        'workerCode': 'D21',
        'name': 'Rahul',
        'status': 'ON_DELIVERY',
        'todaysOrders': 4,
        'activeTasks': [
          {
            'deliveryId': 7,
            'orderId': 42,
            'orderNumber': 'GP10245',
            'deliveryStatus': 'OUT_FOR_DELIVERY',
            'allowedNext': ['DELIVERED', 'FAILED', 'CANCELLED'],
          },
        ],
      });

      expect(profile.activeTasks, hasLength(1));
      expect(profile.activeTasks.single.orderNumber, 'GP10245');
      expect(profile.activeTasks.single.deliveryStatus, 'OUT_FOR_DELIVERY');
      expect(profile.activeTasks.single.allowedNext, contains('DELIVERED'));
    });

    test('a worker with nothing out has an empty list, not a null', () {
      final profile = WorkerProfile.fromJson({
        'workerCode': 'D21',
        'name': 'Rahul',
        'status': 'AVAILABLE',
        'todaysOrders': 0,
      });

      expect(profile.activeTasks, isEmpty);
    });
  });

  group('what the rider is given to find the door', () {
    // The server used to glue the landmark into deliveryAddress and send no
    // instructions at all. Both are their own fields now, and the coordinates
    // are a snapshot taken when the order was placed rather than wherever the
    // customer's saved address points today.
    test('landmark and instructions parse as their own fields', () {
      final order = WorkerOrder.fromJson({
        'orderId': 7,
        'orderNumber': 'GP10254',
        'deliveryAddress': 'House 42, Gupta Nagar, Gorakhpur - 273001',
        'landmark': 'Near Gupta Medical Store',
        'deliveryInstructions': 'Enter from the side lane.',
        'latitude': 26.7606,
        'longitude': 83.3732,
      });

      expect(order.landmark, 'Near Gupta Medical Store');
      expect(order.deliveryInstructions, 'Enter from the side lane.');
      expect(order.deliveryAddress, isNot(contains('Near Gupta Medical Store')),
          reason: 'the landmark is its own line, not glued into the address');
      expect(order.hasDestination, isTrue);
      expect(order.latitude, 26.7606);
      expect(order.longitude, 83.3732);
    });

    test('an order with no confirmed pin reports no destination', () {
      // Pre-map-confirmation orders exist and must not render a Navigate
      // button that opens nowhere.
      final order = WorkerOrder.fromJson({
        'orderId': 8,
        'orderNumber': 'GP00001',
        'deliveryAddress': 'House 9, Civil Lines, Gorakhpur - 273001',
      });

      expect(order.hasDestination, isFalse);
      expect(order.latitude, isNull);
      expect(order.landmark, isNull);
      expect(order.deliveryInstructions, isNull);
    });

    test('a partial coordinate pair is not a destination', () {
      final order = WorkerOrder.fromJson({
        'orderId': 9,
        'orderNumber': 'GP00002',
        'latitude': 26.7606,
      });

      expect(order.hasDestination, isFalse,
          reason: 'half a coordinate navigates nowhere');
    });
  });
}
