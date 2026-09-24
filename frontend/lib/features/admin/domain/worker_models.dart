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

class AdminWorkerProfile {
  const AdminWorkerProfile({
    required this.worker,
    this.shopName,
    required this.totalAssigned,
    required this.completed,
    required this.active,
    required this.exceptions,
    required this.page,
    required this.size,
    required this.hasNext,
    required this.currentWork,
    required this.history,
  });

  final AdminWorker worker;
  final String? shopName;
  final int totalAssigned, completed, active, exceptions, page, size;
  final bool hasNext;
  final List<AdminWorkerDelivery> currentWork, history;

  factory AdminWorkerProfile.fromJson(Map<String, dynamic> json) {
    List<AdminWorkerDelivery> deliveries(String key) =>
        ((json[key] as List?) ?? const [])
            .map((e) => AdminWorkerDelivery.fromJson(e as Map<String, dynamic>))
            .toList(growable: false);
    int number(String key) => (json[key] as num?)?.toInt() ?? 0;
    return AdminWorkerProfile(
      worker: AdminWorker.fromJson(json['worker'] as Map<String, dynamic>),
      shopName: json['shopName'] as String?,
      totalAssigned: number('totalAssigned'), completed: number('completed'),
      active: number('active'), exceptions: number('exceptions'),
      page: number('page'), size: number('size'), hasNext: json['hasNext'] == true,
      currentWork: deliveries('currentWork'), history: deliveries('history'),
    );
  }
}

class AdminWorkerDelivery {
  const AdminWorkerDelivery({required this.id, this.orderNumber, this.status,
    this.assignedAt, this.deliveredAt});
  final int id;
  final String? orderNumber, status;
  final DateTime? assignedAt, deliveredAt;

  factory AdminWorkerDelivery.fromJson(Map<String, dynamic> json) {
    return AdminWorkerDelivery(
      id: (json['deliveryId'] as num).toInt(),
      orderNumber: json['orderNumber'] as String?, status: json['status'] as String?,
      assignedAt: DateTime.tryParse(json['assignedAt'] as String? ?? ''),
      deliveredAt: DateTime.tryParse(json['deliveredAt'] as String? ?? ''),
    );
  }
}
