class AdminCustomer {
  const AdminCustomer({
    required this.id,
    required this.fullName,
    this.email,
    this.mobileNumber,
    required this.active,
    this.role,
  });

  final int id;
  final String fullName;
  final String? email;
  final String? mobileNumber;
  final bool active;
  final String? role;

  factory AdminCustomer.fromJson(Map<String, dynamic> json) {
    return AdminCustomer(
      id: json['id'] as int,
      fullName: json['fullName'] as String? ?? '',
      email: json['email'] as String?,
      mobileNumber: json['mobileNumber'] as String?,
      // Backend field can be genuinely null for very old rows - treat
      // "unknown" as active rather than silently hiding someone from the
      // admin list, since this is a management screen, not a public one.
      active: json['active'] as bool? ?? true,
      role: json['role'] as String?,
    );
  }
}
