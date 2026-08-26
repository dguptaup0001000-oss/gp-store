import '../../../core/notifications/key_value_store.dart';
import '../../../core/logging/app_log.dart';

/// The last few things this customer searched for, kept on the device.
///
/// PRIVACY. Search terms only - no timestamps, no results, no account
/// identifier, nothing that could reconstruct a shopping history beyond the
/// words themselves. It never leaves the phone and is capped at [_maxKept],
/// so this is a convenience, not a record.
///
/// Reuses the KeyValueStore the voice feature already introduced rather than
/// adding a preferences package for six short strings.
class RecentSearches {
  RecentSearches({KeyValueStore? storage})
      : _storage = storage ?? const SecureKeyValueStore();

  static const _key = 'recent_searches';

  /// Newline, because a search term contains spaces - "tata salt" is one
  /// entry, not two, and a space-separated list would split it.
  static const _separator = '\n';
  static const _maxKept = 6;

  final KeyValueStore _storage;

  Future<List<String>> load() async {
    try {
      final raw = await _storage.read(_key);
      if (raw == null || raw.isEmpty) return const [];
      return raw.split(_separator).where((e) => e.trim().isNotEmpty).toList();
    } catch (e) {
      // A convenience feature must never break the screen it decorates.
      appLog('Could not read recent searches: $e');
      return const [];
    }
  }

  /// Records a term, most recent first, without duplicates.
  ///
  /// De-duplicated case-insensitively, but stored with the customer's own
  /// capitalisation - "Tata Salt" should come back as they typed it.
  Future<List<String>> remember(String term) async {
    final trimmed = term.trim();
    if (trimmed.isEmpty) return load();

    try {
      final existing = await load();
      final updated = <String>[trimmed];
      for (final entry in existing) {
        if (entry.toLowerCase() == trimmed.toLowerCase()) continue;
        updated.add(entry);
        if (updated.length == _maxKept) break;
      }

      await _storage.write(_key, updated.join(_separator));
      return updated;
    } catch (e) {
      appLog('Could not save a recent search: $e');
      return load();
    }
  }

  Future<void> clear() async {
    try {
      await _storage.delete(_key);
    } catch (e) {
      appLog('Could not clear recent searches: $e');
    }
  }
}
