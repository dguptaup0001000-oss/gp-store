import 'package:flutter/foundation.dart';
import 'package:flutter_tts/flutter_tts.dart';

/// Speaks a newly-received order aloud on the shop counter phone, the way a
/// payment soundbox announces a transfer.
///
/// WHY THE SHOP NEEDS THIS AT ALL: a phone face-down beside a till is not
/// looked at. A banner is only a notification if somebody sees it; a spoken
/// line is one whether or not anyone is looking.
///
/// STRICTLY ADDITIVE. Nothing here can affect whether an order succeeds. By
/// the time this runs the order is committed, the customer has their
/// confirmation, and the push has already been delivered - this is the last
/// step of a notification, not a step of the transaction. Every method
/// therefore swallows its own failures and logs them, and the caller is
/// never given anything to catch.
class VoiceAnnouncementService {
  VoiceAnnouncementService({FlutterTts? tts}) : _tts = tts ?? FlutterTts();

  final FlutterTts _tts;
  bool _configured = false;

  /// Announces one order.
  ///
  /// [customerName] and [rupees] come from the push's own data fields, which
  /// the backend fills from the committed order - never parsed back out of
  /// the notification's title or body, and never supplied by the client that
  /// placed the order.
  Future<void> announceNewOrder({
    required String customerName,
    required String rupees,
  }) async {
    final line = buildAnnouncement(customerName: customerName, rupees: rupees);
    if (line == null) return;

    try {
      await _configure();
      // Queues rather than interrupts: two orders landing seconds apart on a
      // busy counter should both be heard, not the first cut off mid-name.
      await _tts.speak(line);
    } catch (e) {
      // The order is already placed and the banner already shown. A silent
      // phone is a degraded notification, not a failed sale.
      debugPrint('Voice announcement failed (order and notification unaffected): $e');
    }
  }

  Future<void> _configure() async {
    if (_configured) return;
    try {
      // en-IN so Indian names and number grouping are pronounced the way a
      // shopkeeper here expects, falling back to whatever the device has if
      // that voice is not installed - setLanguage is a no-op rather than an
      // error on an unsupported locale.
      await _tts.setLanguage('en-IN');
      // Slightly under default: announcement speed, not audiobook speed. A
      // total misheard is worse than one heard a beat later.
      await _tts.setSpeechRate(0.48);
      await _tts.setVolume(1.0);
      await _tts.setPitch(1.0);
      await _tts.setQueueMode(1);
      _configured = true;
    } catch (e) {
      debugPrint('TTS configuration failed, falling back to device defaults: $e');
      // Deliberately NOT rethrown and _configured stays false: speaking with
      // default settings is far better than not speaking at all.
    }
  }

  Future<void> stop() async {
    try {
      await _tts.stop();
    } catch (e) {
      debugPrint('Stopping TTS failed: $e');
    }
  }

  /// Builds the spoken line. Pure and public so it can be tested without a
  /// TTS engine, a device, or a running app.
  ///
  /// Returns null when there is nothing worth saying, so the caller stays
  /// silent rather than announcing a half-formed order.
  @visibleForTesting
  static String? buildAnnouncement({
    required String customerName,
    required String rupees,
  }) {
    final name = customerName.trim();
    final amount = _spokenAmount(rupees);
    if (name.isEmpty || amount == null) return null;

    return 'New order received from $name. Order amount $amount.';
  }

  /// Turns the backend's plain amount into words a person says.
  ///
  /// The backend sends a bare number - no symbol, no grouping - precisely so
  /// this can decide how it is said. Speaking "₹" would come out as "rupee
  /// symbol", and "520.00" as "five hundred twenty point zero zero".
  ///
  ///   520     -> "520 rupees"
  ///   1       -> "1 rupee"          (not "1 rupees")
  ///   780.50  -> "780 rupees 50 paise"
  ///   0.50    -> "50 paise"          (no "0 rupees" prefix)
  ///
  /// Returns null for anything unparseable, so a malformed payload produces
  /// silence rather than the app reading a raw string aloud.
  static String? _spokenAmount(String raw) {
    final value = double.tryParse(raw.trim());
    if (value == null || value < 0) return null;

    final whole = value.floor();
    // Rounded, not truncated: floating point turns 780.50 into 780.49999...,
    // and truncating would announce 49 paise.
    final paise = ((value - whole) * 100).round();

    // Carry, for the case where rounding pushes paise to a full rupee.
    final rupees = paise == 100 ? whole + 1 : whole;
    final remainder = paise == 100 ? 0 : paise;

    final rupeeWord = rupees == 1 ? 'rupee' : 'rupees';

    if (rupees == 0 && remainder == 0) return null;
    if (remainder == 0) return '$rupees $rupeeWord';
    if (rupees == 0) return '$remainder paise';
    return '$rupees $rupeeWord $remainder paise';
  }
}
