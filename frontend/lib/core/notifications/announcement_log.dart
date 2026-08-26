import 'dart:async';

import 'key_value_store.dart';
import '../logging/app_log.dart';

/// Remembers which orders have already been announced, so the same order is
/// never spoken twice.
///
/// KEYED ON THE ORDER ID AND NOTHING ELSE. Two different customers can share
/// a name, and two orders can share an amount - within a single busy hour
/// both are likely. Only the order id is unique, so only the order id can
/// decide whether something has already been heard.
///
/// PERSISTED, NOT JUST IN MEMORY. Every one of these delivers the same
/// message twice or more, and an in-memory set survives none of them:
///
///   - FCM's at-least-once delivery redelivering a message
///   - the background isolate handling a push, then the foreground isolate
///     handling the same one when the shop reopens the app
///   - the app being killed by Android and relaunched between deliveries
///   - a backend retry of the same notification
///
/// Storage is flutter_secure_storage because it is already a dependency and
/// this needs no new package. It is heavier than a plain preferences file
/// (Android keystore on every write), which is acceptable at one write per
/// order but would not be at one write per second.
///
/// BOUNDED. Only the most recent [_maxRemembered] ids are kept, so a shop
/// running for years does not accumulate an unbounded string. That does mean
/// an order could in principle be re-announced after that many newer orders
/// have arrived - which requires a duplicate delivery arriving hundreds of
/// orders late, and is a trade worth making against unbounded growth.
class AnnouncementLog {
  AnnouncementLog({KeyValueStore? storage})
      : _storage = storage ?? const SecureKeyValueStore();

  static const _key = 'voice_announced_order_ids';
  static const _maxRemembered = 200;
  static const _separator = ',';

  final KeyValueStore _storage;

  /// Serialises read-modify-write. Two pushes arriving in the same moment
  /// would otherwise both read the old list, both append, and the second
  /// write would erase the first - so a duplicate could slip through the
  /// very check meant to stop it.
  Future<void> _lock = Future<void>.value();

  /// Records [orderId] and reports whether this call is the FIRST to see it.
  ///
  /// Returns true exactly once per order id: the caller should announce only
  /// when it gets true. Any later call for the same id returns false.
  ///
  /// On a storage failure it returns true - announcing a duplicate is a far
  /// better failure than a shop never hearing a real order because the
  /// keystore was briefly unavailable.
  Future<bool> claim(String orderId) {
    final completer = Completer<bool>();

    _lock = _lock.then((_) async {
      try {
        completer.complete(await _claimUnsynchronised(orderId));
      } catch (e) {
        appLog('Announcement dedup failed, allowing the announcement: $e');
        completer.complete(true);
      }
    });

    return completer.future;
  }

  Future<bool> _claimUnsynchronised(String orderId) async {
    final id = orderId.trim();
    if (id.isEmpty) {
      // No id means nothing can be deduplicated. Refuse rather than announce
      // something that could then repeat forever.
      return false;
    }

    final raw = await _storage.read(_key);
    final seen = (raw == null || raw.isEmpty)
        ? <String>[]
        : raw.split(_separator).where((e) => e.isNotEmpty).toList();

    if (seen.contains(id)) return false;

    seen.add(id);
    // Oldest first, so trimming from the front drops the least recent.
    final trimmed = seen.length > _maxRemembered
        ? seen.sublist(seen.length - _maxRemembered)
        : seen;

    await _storage.write(_key, trimmed.join(_separator));
    return true;
  }

  /// Clears the record. Only for tests and troubleshooting - never called in
  /// normal operation, since forgetting is exactly what this exists to stop.
  @visibleForTesting
  Future<void> clear() => _storage.delete(_key);
}
