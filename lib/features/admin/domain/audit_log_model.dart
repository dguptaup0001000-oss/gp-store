class AuditLogEntry {
  const AuditLogEntry({
    required this.id,
    this.actorEmail,
    this.actorRole,
    required this.action,
    required this.entityType,
    this.entityId,
    this.details,
    this.occurredAt,
  });

  final int id;
  final String? actorEmail;
  final String? actorRole;
  final String action;
  final String entityType;
  final int? entityId;
  final String? details;
  final String? occurredAt;

  factory AuditLogEntry.fromJson(Map<String, dynamic> json) {
    return AuditLogEntry(
      id: json['id'] as int,
      actorEmail: json['actorEmail'] as String?,
      actorRole: json['actorRole'] as String?,
      action: json['action'] as String,
      entityType: json['entityType'] as String,
      entityId: json['entityId'] as int?,
      details: json['details'] as String?,
      occurredAt: json['occurredAt'] as String?,
    );
  }
}
