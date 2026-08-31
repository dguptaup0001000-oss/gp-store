/// The shop's own answer to "are you open, and when will this arrive?".
///
/// PLAIN DART, NOT FREEZED, deliberately. Every other model in this app is
/// generated because it is a pure data carrier; this one is not — it holds a
/// [Stopwatch] and derives a live countdown from it, which is behaviour a
/// generated `copyWith` would happily copy into an inconsistent state.
///
/// THE DEVICE CLOCK IS NEVER TRUSTED FOR AN ABSOLUTE TIME. A phone set twenty
/// minutes fast would otherwise show "same-day ordering closed" while the vans
/// are still out, and a phone set slow would show it open after close and send
/// someone to a checkout that refuses them. What this class uses the device for
/// is ELAPSED time — how long since the response arrived — which a wrong clock
/// still measures correctly, and which a [Stopwatch] measures monotonically so
/// that a clock change mid-session cannot move it either.
library;

enum StoreMode { sameDay, night, unknown }

enum StoreDeliveryType { sameDay, nextMorning, manualScheduled, unknown }

class StoreStatus {
  StoreStatus({
    required this.browsingOpen,
    required this.acceptingOrders,
    required this.mode,
    required this.deliveryType,
    required this.deliveryDate,
    required this.deliveryStartTime,
    required this.deliveryEndTime,
    required this.closedToday,
    required this.message,
    required int? countdownSeconds,
  })  : _countdownSecondsAtFetch = countdownSeconds,
        _sinceFetch = Stopwatch()..start();

  /// ALWAYS TRUE from the server. Kept as a field rather than assumed, so the
  /// day something starts sending false, the app shows it rather than
  /// cheerfully contradicting the shop.
  final bool browsingOpen;

  /// Whether checkout would be accepted. ADVISORY: the server re-checks and
  /// refuses on its own, so a stale `true` here costs a clear error message,
  /// not a bad order.
  final bool acceptingOrders;

  final StoreMode mode;
  final StoreDeliveryType deliveryType;

  /// The shop-local day an order placed now would arrive. Null when the shop
  /// has no reachable delivery day.
  final DateTime? deliveryDate;

  /// The shop's hours, as text like "09:00". Read from the server rather than
  /// written into the app, so changing the hours does not need a new release.
  final String? deliveryStartTime;
  final String? deliveryEndTime;

  final bool closedToday;

  /// The shop's own words about why orders are paused, or null.
  final String? message;

  final int? _countdownSecondsAtFetch;
  final Stopwatch _sinceFetch;

  /// Seconds of same-day ordering left, or null when there is no countdown.
  ///
  /// Recomputed on every read from the server's figure minus however long this
  /// object has existed. Clamped at zero rather than going negative: a banner
  /// reading "closes in -3 minutes" is worse than one that has stopped.
  int? get countdownSeconds {
    final start = _countdownSecondsAtFetch;
    if (start == null) return null;
    final remaining = start - _sinceFetch.elapsed.inSeconds;
    return remaining > 0 ? remaining : 0;
  }

  /// Whether the "same-day ordering closes soon" warning should show.
  ///
  /// Goes false once the countdown reaches zero, so a screen left open across
  /// 21:00 stops promising a van rather than sitting at "closes in 0 minutes"
  /// until the next poll.
  bool get countdownActive => (countdownSeconds ?? 0) > 0;

  /// How long this answer has been held, so a caller can tell a fresh status
  /// from one fetched before the phone went to sleep.
  Duration get age => _sinceFetch.elapsed;

  factory StoreStatus.fromJson(Map<String, dynamic> json) {
    return StoreStatus(
      // Defaults to open. If the field is ever missing, a client that assumed
      // "closed" would hide the entire catalogue over a serialisation
      // mismatch — the one failure this feature must never produce.
      browsingOpen: json['browsingOpen'] as bool? ?? true,
      acceptingOrders: json['acceptingOrders'] as bool? ?? true,
      mode: _modeFrom(json['mode'] as String?),
      deliveryType: _typeFrom(json['deliveryType'] as String?),
      deliveryDate: _dateFrom(json['deliveryDate']),
      deliveryStartTime: json['deliveryStartTime'] as String?,
      deliveryEndTime: json['deliveryEndTime'] as String?,
      closedToday: json['closedToday'] as bool? ?? false,
      message: json['message'] as String?,
      countdownSeconds: (json['countdownSeconds'] as num?)?.toInt(),
    );
  }

  /// What to assume when the shop cannot be reached at all.
  ///
  /// OPEN FOR BROWSING AND ORDERING. The alternative — assuming closed — turns
  /// a dropped request into a shop that appears shut, which is the worse error
  /// by a wide margin: the server refuses an order it cannot accept anyway, so
  /// the cost of being wrong this way is one clear message at checkout, while
  /// the cost of the other way is a customer who leaves believing GP STORE is
  /// closed. The delivery promise is left null rather than guessed, so nothing
  /// tells the customer a time the shop has not confirmed.
  factory StoreStatus.unknown() => StoreStatus(
        browsingOpen: true,
        acceptingOrders: true,
        mode: StoreMode.unknown,
        deliveryType: StoreDeliveryType.unknown,
        deliveryDate: null,
        deliveryStartTime: null,
        deliveryEndTime: null,
        closedToday: false,
        message: null,
        countdownSeconds: null,
      );

  static StoreMode _modeFrom(String? raw) => switch (raw) {
        'SAME_DAY' => StoreMode.sameDay,
        'NIGHT' => StoreMode.night,
        _ => StoreMode.unknown,
      };

  static StoreDeliveryType _typeFrom(String? raw) => switch (raw) {
        'SAME_DAY' => StoreDeliveryType.sameDay,
        'NEXT_MORNING' => StoreDeliveryType.nextMorning,
        'MANUAL_SCHEDULED' => StoreDeliveryType.manualScheduled,
        _ => StoreDeliveryType.unknown,
      };

  static DateTime? _dateFrom(Object? raw) {
    if (raw is! String || raw.isEmpty) return null;
    return DateTime.tryParse(raw);
  }
}
