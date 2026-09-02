/// A delivery worker as the shop's roster page sees them.
///
/// A PLAIN CLASS, not freezed. Nothing here needs copyWith or value equality -
/// the screen refetches after every write - and a hand-written fromJson is one
/// less thing that has to be regenerated before the app will compile.
///
/// THERE IS NO PASSWORD FIELD, deliberately. The server never returns one, and
/// a field that is always null is an invitation to try to render it.
class AdminWorker {
  const AdminWorker({
    required this.id,
    required this.name,
    required this.loginEmail,
    this.mobile,
    this.vehicleType,
    this.vehicleNumber,
    required this.available,
    required this.active,
    required this.canSignIn,
    required this.suspended,
    this.suspendedUntil,
    this.suspensionReason,
  });

  final int id;
  final String name;
  final String loginEmail;
  final String? mobile;
  final String? vehicleType;
  final String? vehicleNumber;

  /// Whether dispatch may hand them work right now.
  final bool available;

  /// The shop's on/off switch. Off is indefinite; a pause is not.
  final bool active;

  /// The one the roster actually cares about: can they open the app today?
  final bool canSignIn;

  final bool suspended;

  /// Only ever set while the pause is still running - the server drops a
  /// stale timestamp rather than letting last week's pause render as a live
  /// one.
  final DateTime? suspendedUntil;
  final String? suspensionReason;

  static String? _string(Object? value) =>
      value is String && value.trim().isNotEmpty ? value.trim() : null;

  factory AdminWorker.fromJson(Map<String, dynamic> json) {
    final until = _string(json['suspendedUntil']);
    return AdminWorker(
      id: (json['id'] as num).toInt(),
      name: _string(json['name']) ?? '',
      loginEmail: _string(json['loginEmail']) ?? '',
      mobile: _string(json['mobile']),
      vehicleType: _string(json['vehicleType']),
      vehicleNumber: _string(json['vehicleNumber']),
      available: json['available'] == true,
      active: json['active'] == true,
      canSignIn: json['canSignIn'] == true,
      suspended: json['suspended'] == true,
      // tryParse, not parse: a date the server sends in a shape this app did
      // not expect must not take down the whole roster screen.
      suspendedUntil: until == null ? null : DateTime.tryParse(until),
      suspensionReason: _string(json['suspensionReason']),
    );
  }

  /// What the shop is told about this worker's login, in one line.
  String get statusLine {
    if (suspended) {
      final reason = suspensionReason;
      return reason == null ? 'Paused' : 'Paused - $reason';
    }
    if (!active) return 'Switched off';
    if (!canSignIn) return 'Cannot sign in yet';
    return available ? 'Working' : 'Signed in, not taking deliveries';
  }
}
