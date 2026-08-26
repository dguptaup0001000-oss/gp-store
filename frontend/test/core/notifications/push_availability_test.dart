import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/notifications/push_availability.dart';

void main() {
  test('push is unavailable until Firebase initializes', () {
    PushAvailability.firebaseReady = false;
    expect(PushAvailability.firebaseReady, isFalse);
    PushAvailability.firebaseReady = true;
    expect(PushAvailability.firebaseReady, isTrue);
    PushAvailability.firebaseReady = false;
  });
}
