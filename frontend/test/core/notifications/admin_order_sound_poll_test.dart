import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/notifications/admin_order_sound_poll.dart';

void main() {
  group('AdminOrderSoundPoll', () {
    test('the arming call never announces historical orders', () {
      final poll = AdminOrderSoundPoll();
      final spoken = poll.ingest(
        responseAfterId: 42,
        orders: [
          const AdminNewOrderAlert(
            orderId: '41',
            customerName: 'Old',
            orderAmount: '10',
          ),
        ],
        firstCall: true,
      );
      expect(spoken, isEmpty);
      expect(poll.afterId, 42);
      expect(poll.armed, isTrue);
    });

    test('later polls announce the orders the server returned', () {
      final poll = AdminOrderSoundPoll();
      poll.ingest(responseAfterId: 42, orders: const [], firstCall: true);
      final spoken = poll.ingest(
        responseAfterId: 44,
        orders: const [
          AdminNewOrderAlert(
            orderId: '43',
            customerName: 'Ramesh Kumar',
            orderAmount: '520',
          ),
          AdminNewOrderAlert(
            orderId: '44',
            customerName: 'Priya',
            orderAmount: '780.50',
          ),
        ],
        firstCall: false,
      );
      expect(spoken.map((a) => a.orderId).toList(), ['43', '44']);
      expect(poll.afterId, 44);
    });

    test('an empty later poll stays silent and keeps the high-water mark', () {
      final poll = AdminOrderSoundPoll();
      poll.ingest(responseAfterId: 42, orders: const [], firstCall: true);
      final spoken = poll.ingest(
        responseAfterId: 42,
        orders: const [],
        firstCall: false,
      );
      expect(spoken, isEmpty);
      expect(poll.afterId, 42);
    });

    test('reset disarms so the next session does not dump a backlog', () {
      final poll = AdminOrderSoundPoll();
      poll.ingest(responseAfterId: 42, orders: const [], firstCall: true);
      poll.reset();
      expect(poll.armed, isFalse);
      expect(poll.afterId, isNull);
    });

    test('fromJson accepts a numeric orderId from Jackson', () {
      final alert = AdminNewOrderAlert.fromJson({
        'orderId': 43,
        'customerName': 'Ramesh Kumar',
        'orderAmount': '520',
      });
      expect(alert.orderId, '43');
      expect(alert.customerName, 'Ramesh Kumar');
      expect(alert.orderAmount, '520');
    });
  });
}
