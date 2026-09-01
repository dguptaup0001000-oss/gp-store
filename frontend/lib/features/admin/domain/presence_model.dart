/// How many people are using the shop right now, as the backend reports it.
///
/// [onlineNow] is nullable and that is the whole point of this class existing
/// rather than the provider returning a bare int. When Redis is unavailable
/// the backend answers `available: false` with no number, because "0 people
/// are shopping" and "I cannot tell you how many people are shopping" are
/// different statements and a shopkeeper acts differently on each. Collapsing
/// the second into 0 would quietly tell them the shop had emptied.
///
/// [windowSeconds] travels with the number for the same reason: "12 online"
/// is not a fact until you know over what period it was counted.
class PresenceSnapshot {
  const PresenceSnapshot({
    required this.onlineNow,
    required this.windowSeconds,
    required this.available,
  });

  final int? onlineNow;
  final int windowSeconds;
  final bool available;

  factory PresenceSnapshot.fromJson(Map<String, dynamic> json) {
    return PresenceSnapshot(
      onlineNow: (json['onlineNow'] as num?)?.toInt(),
      windowSeconds: (json['windowSeconds'] as num?)?.toInt() ?? 300,
      available: json['available'] as bool? ?? false,
    );
  }

  /// "in the last 5 minutes" - the definition, in words, next to the number.
  String get windowLabel {
    if (windowSeconds % 60 == 0) {
      final minutes = windowSeconds ~/ 60;
      return minutes == 1 ? 'in the last minute' : 'in the last $minutes minutes';
    }
    return 'in the last $windowSeconds seconds';
  }
}
