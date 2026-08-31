import 'store_status.dart';

/// Every sentence the app says about the shop's hours, in one place.
///
/// WHY THIS IS SEPARATE FROM THE WIDGET. The same fact is stated on the home
/// banner, on the checkout button and in the order history, and three widgets
/// each writing their own sentence is how a customer gets told "arriving
/// tomorrow" by one screen and "arriving today" by the next. These are pure
/// functions of a [StoreStatus], so they are also testable without pumping a
/// widget.
///
/// THE TONE IS THE POINT. GP STORE is open; only the van has stopped. Nothing
/// here may say "closed" for a shop that is taking orders — see
/// [browsingNeverCloses].
class StoreStatusCopy {
  const StoreStatusCopy._();

  /// The headline on the home banner.
  static String? banner(StoreStatus status) {
    if (!status.acceptingOrders) {
      return status.message ?? 'Orders are paused for the moment';
    }
    if (status.countdownActive) {
      return 'Same-day delivery closes in ${countdown(status)}';
    }
    return switch (status.mode) {
      StoreMode.night => 'Order now, delivered from ${_startTime(status)}',
      StoreMode.sameDay => null,
      StoreMode.unknown => null,
    };
  }

  /// The supporting line under the headline, or null when it would only repeat.
  static String? bannerDetail(StoreStatus status) {
    if (!status.acceptingOrders) {
      return 'You can still browse and fill your basket.';
    }
    if (status.closedToday) {
      return 'No deliveries today. Orders placed now go out on the next '
          'delivery day.';
    }
    return switch (status.mode) {
      StoreMode.night =>
        "We're open all night — deliveries start at ${_startTime(status)}.",
      _ => null,
    };
  }

  /// "12 min" / "45 sec". Minutes once past a minute, because a second-by-second
  /// figure at ten minutes out reads as urgency the customer does not need.
  static String countdown(StoreStatus status) {
    final seconds = status.countdownSeconds ?? 0;
    if (seconds >= 60) {
      final minutes = (seconds / 60).ceil();
      return '$minutes min';
    }
    return '$seconds sec';
  }

  /// What the customer is promised at checkout.
  ///
  /// NULL WHEN THE SERVER HAS NOT SAID. An app that cannot reach the shop must
  /// not invent a delivery time; silence is honest, and the order confirmation
  /// carries the real answer the server decided.
  static String? deliveryPromise(StoreStatus status) {
    return switch (status.deliveryType) {
      StoreDeliveryType.sameDay => 'Arriving today',
      StoreDeliveryType.nextMorning =>
        'Arriving from ${_startTime(status)}${_dayQualifier(status)}',
      StoreDeliveryType.manualScheduled => 'Scheduled delivery',
      StoreDeliveryType.unknown => null,
    };
  }

  /// The label on an order in the customer's history.
  ///
  /// Takes the type STORED ON THE ORDER, not today's status: history is a
  /// record of what happened, and re-deriving it from the current hour would
  /// relabel last week's orders every evening.
  static String? historyLabel(String? storedDeliveryType) {
    return switch (storedDeliveryType) {
      'SAME_DAY' => 'Same-day delivery',
      'NEXT_MORNING' => 'Next-morning delivery',
      'MANUAL_SCHEDULED' => 'Scheduled delivery',
      // Orders placed before the shop had delivery windows. Shown as nothing
      // rather than guessed at — a label invented for a delivery that already
      // happened would be a plain untruth.
      _ => null,
    };
  }

  /// TRUE, ALWAYS, and asserted by test.
  ///
  /// The single rule the whole feature rests on: GP STORE can be shopped at any
  /// hour. If a future change ever wants to gate the catalogue, it has to come
  /// through this method and make the lie explicit.
  static bool browsingNeverCloses(StoreStatus status) => status.browsingOpen;

  static String _startTime(StoreStatus status) {
    // Falls back to a generic phrase rather than a hardcoded "9 AM": the hours
    // are the server's to state, and an app that has not heard from it must
    // not quote a time of its own invention.
    final raw = status.deliveryStartTime;
    if (raw == null || raw.isEmpty) return 'opening time';
    return _friendlyTime(raw);
  }

  /// "09:00" -> "9 AM", "21:00" -> "9 PM", "09:30" -> "9:30 AM".
  static String _friendlyTime(String raw) {
    final parts = raw.split(':');
    final hour = int.tryParse(parts.first);
    if (hour == null) return raw;
    final minute = parts.length > 1 ? int.tryParse(parts[1]) ?? 0 : 0;
    final suffix = hour < 12 ? 'AM' : 'PM';
    final twelve = hour % 12 == 0 ? 12 : hour % 12;
    return minute == 0
        ? '$twelve $suffix'
        : '$twelve:${minute.toString().padLeft(2, '0')} $suffix';
  }

  /// " tomorrow" when the delivery day is not today, and nothing when it is.
  ///
  /// Compares only the calendar date, and only when the server supplied one —
  /// an order placed at 07:00 is delivered at 9 AM the SAME day, and calling
  /// that "tomorrow" would be the most obvious possible bug in this feature.
  static String _dayQualifier(StoreStatus status) {
    final date = status.deliveryDate;
    if (date == null) return '';
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final target = DateTime(date.year, date.month, date.day);
    final days = target.difference(today).inDays;
    if (days <= 0) return '';
    if (days == 1) return ' tomorrow';
    return ' on ${_weekday(target.weekday)}';
  }

  static String _weekday(int weekday) => switch (weekday) {
        DateTime.monday => 'Monday',
        DateTime.tuesday => 'Tuesday',
        DateTime.wednesday => 'Wednesday',
        DateTime.thursday => 'Thursday',
        DateTime.friday => 'Friday',
        DateTime.saturday => 'Saturday',
        _ => 'Sunday',
      };
}
