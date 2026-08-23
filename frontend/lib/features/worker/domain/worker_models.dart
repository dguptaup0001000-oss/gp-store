/// Plain data classes, hand-written on purpose.
///
/// The rest of this app uses freezed and json_serializable, which are worth it
/// for the customer-facing models that change often and are used everywhere.
/// These three are read in one place each and will not change shape, and the
/// worker APK's whole point is to open and scan in seconds - keeping generated
/// code out of it means one less build step and nothing to regenerate when
/// somebody clones the repo.
library;

/// Who the worker is, and what the home screen shows.
class WorkerProfile {
  const WorkerProfile({
    required this.workerCode,
    required this.name,
    this.zoneCode,
    this.subzoneCode,
    this.subzoneName,
    required this.status,
    required this.todaysOrders,
  });

  /// D21. Comes from the server - the app never invents or sends it.
  final String workerCode;
  final String name;

  /// Null until an administrator has drawn territories and assigned this
  /// worker to one. The home screen says so rather than showing a blank.
  final String? zoneCode;
  final String? subzoneCode;
  final String? subzoneName;

  /// AVAILABLE, ON_DELIVERY or OFFLINE.
  final String status;
  final int todaysOrders;

  factory WorkerProfile.fromJson(Map<String, dynamic> json) => WorkerProfile(
        workerCode: (json['workerCode'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        zoneCode: json['zoneCode'] as String?,
        subzoneCode: json['subzoneCode'] as String?,
        subzoneName: json['subzoneName'] as String?,
        status: (json['status'] ?? 'OFFLINE') as String,
        todaysOrders: (json['todaysOrders'] as num?)?.toInt() ?? 0,
      );
}

/// What the server said about one scan.
class ScanOutcome {
  const ScanOutcome({
    required this.accepted,
    required this.outcome,
    required this.message,
    this.orderNumber,
    this.subzoneCode,
    this.replayed = false,
    this.queued = false,
  });

  final bool accepted;

  /// ACCEPTED, ALREADY_SCANNED, NOT_AUTHORISED, NOT_ELIGIBLE, UNKNOWN_TOKEN,
  /// WORKER_INACTIVE - or QUEUED, which only this app ever produces.
  final String outcome;
  final String message;
  final String? orderNumber;
  final String? subzoneCode;

  /// True when the server replayed an earlier answer to a retried scan.
  final bool replayed;

  /// True when the scan never reached the server and is waiting on the phone.
  ///
  /// Kept strictly separate from [accepted]. A queued scan has NOT been
  /// recorded, and telling a worker otherwise would have them walk away from a
  /// carton nobody is accountable for.
  final bool queued;

  factory ScanOutcome.fromJson(Map<String, dynamic> json) => ScanOutcome(
        accepted: json['accepted'] == true,
        outcome: (json['outcome'] ?? 'UNKNOWN') as String,
        message: (json['message'] ?? '') as String,
        orderNumber: json['orderNumber'] as String?,
        subzoneCode: json['subzoneCode'] as String?,
        replayed: json['replayed'] == true,
      );

  static const ScanOutcome offline = ScanOutcome(
    accepted: false,
    outcome: 'QUEUED',
    message: 'Connection unavailable. Scan will be submitted when connection returns.',
    queued: true,
  );
}

/// One line of "what I did today".
class WorkerScanRow {
  const WorkerScanRow({
    this.orderNumber,
    required this.outcome,
    this.reason,
    this.subzoneCode,
    this.scannedAt,
  });

  final String? orderNumber;
  final String outcome;
  final String? reason;
  final String? subzoneCode;
  final DateTime? scannedAt;

  bool get accepted => outcome == 'ACCEPTED';

  factory WorkerScanRow.fromJson(Map<String, dynamic> json) => WorkerScanRow(
        orderNumber: json['orderNumber'] as String?,
        outcome: (json['outcome'] ?? '') as String,
        reason: json['reason'] as String?,
        subzoneCode: json['subzoneCode'] as String?,
        scannedAt: json['scannedAt'] == null
            ? null
            : DateTime.tryParse(json['scannedAt'].toString()),
      );
}
