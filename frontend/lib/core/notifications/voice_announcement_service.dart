import 'dart:async';

import 'package:flutter/foundation.dart';

import 'announcement_log.dart';
import 'speech_engine.dart';
import 'voice_settings.dart';
import '../logging/app_log.dart';

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
/// swallows its own failures and logs them; the caller is never given
/// anything to catch.
class VoiceAnnouncementService {
  VoiceAnnouncementService({
    SpeechEngine? engine,
    AnnouncementLog? log,
    VoiceSettings? settings,
  })  : _engine = engine ?? FlutterTtsSpeechEngine(),
        _log = log ?? AnnouncementLog(),
        _settings = settings ?? VoiceSettings();

  final SpeechEngine _engine;
  final AnnouncementLog _log;
  final VoiceSettings _settings;

  bool _configured = false;

  /// One announcement at a time, in arrival order.
  ///
  /// Three orders landing within a few seconds on a busy counter must be
  /// heard as three separate lines, not as overlapping speech - and the
  /// right name must stay attached to the right amount. Each announcement
  /// appends to this chain and waits for the previous one to finish
  /// speaking, so the queue is the chain itself rather than a separate
  /// structure that could drift out of step with what is actually playing.
  Future<void> _queue = Future<void>.value();

  /// Announces one order, at most once ever.
  ///
  /// [orderId] is the deduplication key and nothing else - it is never
  /// spoken. [customerName] and [rupees] come from the push's own data
  /// fields, which the backend fills from the committed order: never parsed
  /// out of the notification text, and never supplied by the client that
  /// placed the order.
  Future<void> announceNewOrder({
    required String orderId,
    required String customerName,
    required String rupees,
  }) async {
    try {
      // Build FIRST, before claiming the id. A malformed payload should not
      // consume the one chance this order has to be announced - if a valid
      // duplicate of it arrives later, that one should still be spoken.
      final line =
          buildAnnouncement(customerName: customerName, rupees: rupees);
      if (line == null) {
        appLog('Skipping announcement for order $orderId: '
            'name or amount missing or unparseable in the push payload');
        return;
      }

      if (!await _settings.isEnabled()) return;

      // Claimed after the settings check so that turning the voice off does
      // not silently burn the id - an order that arrived while muted can
      // still be announced if it is redelivered after the shop turns voice
      // back on.
      if (!await _log.claim(orderId)) return;

      _enqueue(line);
    } catch (e) {
      appLog(
          'Voice announcement failed (order and notification unaffected): $e');
    }
  }

  void _enqueue(String line) {
    _queue = _queue.then((_) async {
      try {
        await _configure();
        // Resolves only when this line has finished being spoken (see
        // awaitSpeakCompletion in FlutterTtsSpeechEngine.configure), which is
        // what makes the chain above an actual queue rather than three
        // overlapping voices.
        await _engine.speak(line);
      } catch (e) {
        // Swallowed inside the chain on purpose: one failed line must not
        // break the chain and mute every order after it.
        appLog('Speaking an announcement failed: $e');
      }
    });
  }

  Future<void> _configure() async {
    if (_configured) return;
    try {
      await _engine.configure();
      _configured = true;
    } catch (e) {
      appLog('TTS configuration failed, falling back to device defaults: $e');
      // Deliberately not rethrown, and _configured stays false: speaking with
      // default settings is far better than not speaking at all.
    }
  }

  /// Completes when everything queued so far has finished being spoken.
  ///
  /// For tests only. Production callers deliberately do not await the queue -
  /// a push handler must not be held open for the length of an announcement.
  @visibleForTesting
  Future<void> drainForTest() => _queue;

  /// Stops any announcement in progress. Used on teardown, not as a way to
  /// skip a queued order.
  Future<void> stop() async {
    try {
      await _engine.stop();
    } catch (e) {
      appLog('Stopping TTS failed: $e');
    }
  }

  /// Builds the spoken line. Pure and public so the wording can be tested
  /// without a TTS engine, a device, or a running app.
  ///
  /// Returns null when there is nothing worth saying, so the caller stays
  /// silent rather than announcing a half-formed order.
  ///
  /// PRIVACY: name and amount are the only inputs this takes, so there is no
  /// path by which a phone number, address, email, payment detail or
  /// database id could reach the speaker - not by policy, by signature.
  @visibleForTesting
  static String? buildAnnouncement({
    required String customerName,
    required String rupees,
  }) {
    final name = _sayableName(customerName);
    final amount = _spokenAmount(rupees);
    if (name == null || amount == null) return null;

    return 'New order received from $name. Order amount $amount.';
  }

  /// Guards against reading raw data aloud.
  ///
  /// A push whose name field arrived as the literal string "null" - a
  /// stringified absent value, which is exactly what a serialisation bug
  /// produces - must not become "New order received from null." Rejected
  /// rather than substituted, so the problem is logged and investigated
  /// instead of quietly papered over.
  static String? _sayableName(String raw) {
    final name = raw.trim();
    if (name.isEmpty) return null;

    final lowered = name.toLowerCase();
    if (lowered == 'null' || lowered == 'undefined' || lowered == 'nan')
      return null;

    // Anything that looks like structured data rather than a person.
    if (name.contains('{') || name.contains('}') || name.contains('"'))
      return null;

    return name;
  }

  /// Turns the backend's plain amount into words a person says.
  ///
  /// The backend sends a bare number - no symbol, no grouping - precisely so
  /// this can decide how it is said. Speaking "₹" would come out as "rupee
  /// symbol", and "520.00" as "five hundred twenty point zero zero".
  ///
  ///   520     -> "520 rupees"
  ///   1       -> "1 rupee"           (not "1 rupees")
  ///   780.50  -> "780 rupees 50 paise"
  ///   0.50    -> "50 paise"          (no "0 rupees" prefix)
  ///
  /// Returns null for anything unparseable, so a malformed payload produces
  /// silence rather than the app reading a raw string aloud.
  static String? _spokenAmount(String raw) {
    final value = double.tryParse(raw.trim());
    if (value == null || value.isNaN || value.isInfinite || value < 0)
      return null;

    final whole = value.floor();
    // Rounded, not truncated: floating point turns 780.50 into 780.49999...,
    // and truncating would announce 49 paise.
    final paise = ((value - whole) * 100).round();

    // Carry, for when rounding pushes paise up to a whole rupee.
    final rupees = paise == 100 ? whole + 1 : whole;
    final remainder = paise == 100 ? 0 : paise;

    final rupeeWord = rupees == 1 ? 'rupee' : 'rupees';

    if (rupees == 0 && remainder == 0) return null;
    if (remainder == 0) return '$rupees $rupeeWord';
    if (rupees == 0) return '$remainder paise';
    return '$rupees $rupeeWord $remainder paise';
  }
}
