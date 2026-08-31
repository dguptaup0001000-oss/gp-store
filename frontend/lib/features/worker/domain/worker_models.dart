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
    this.activeTasks = const [],
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

  /// The deliveries assigned to this worker and still running.
  ///
  /// Arrives with the profile rather than from its own request. The home
  /// screen has nothing to draw until both have answered, so two calls would
  /// only mean the slower one arriving later - on the screen that opens most
  /// often, on the worst connection in the business.
  final List<WorkerTask> activeTasks;

  factory WorkerProfile.fromJson(Map<String, dynamic> json) => WorkerProfile(
        workerCode: (json['workerCode'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        zoneCode: json['zoneCode'] as String?,
        subzoneCode: json['subzoneCode'] as String?,
        subzoneName: json['subzoneName'] as String?,
        status: (json['status'] ?? 'OFFLINE') as String,
        todaysOrders: (json['todaysOrders'] as num?)?.toInt() ?? 0,
        activeTasks: (json['activeTasks'] as List?)
                ?.map((e) =>
                    WorkerTask.fromJson(Map<String, dynamic>.from(e as Map)))
                .toList(growable: false) ??
            const [],
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
    this.order,
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

  /// The order itself, on an accepted scan. Null on a refusal or a replay.
  ///
  /// Carried on the scan response so SCAN -> VERIFY -> SHOW ORDER is ONE
  /// round trip. Fetching the order separately afterwards would put a second
  /// request on the critical path of every scan of the day, on a connection
  /// where that is the difference between reading a packing list and watching
  /// a spinner.
  final WorkerOrder? order;

  factory ScanOutcome.fromJson(Map<String, dynamic> json) => ScanOutcome(
        accepted: json['accepted'] == true,
        outcome: (json['outcome'] ?? 'UNKNOWN') as String,
        message: (json['message'] ?? '') as String,
        orderNumber: json['orderNumber'] as String?,
        subzoneCode: json['subzoneCode'] as String?,
        replayed: json['replayed'] == true,
        // Map<String, dynamic>.from, not a cast. A decoded JSON object is
        // usually already Map<String, dynamic>, but not always - and when the
        // cast missed, an ACCEPTED scan threw a TypeError instead of opening
        // the packing list, telling the worker their scan had failed when the
        // server had recorded it. WorkerProfile.activeTasks was already
        // defensive here; these two were not.
        order: json['order'] == null
            ? null
            : WorkerOrder.fromJson(
                Map<String, dynamic>.from(json['order'] as Map)),
      );

  static const ScanOutcome offline = ScanOutcome(
    accepted: false,
    outcome: 'QUEUED',
    message:
        'Connection unavailable. Scan will be submitted when connection returns.',
    queued: true,
  );
}

/// One order, as the worker screen shows it.
///
/// Mirrors the server's WorkerOrderView exactly, including its omissions: no
/// prices per line, no GST, no discounts, no payment instrument. A worker packs
/// a carton and carries it to a door, and [amountToCollect] is the only money
/// on this screen because it is the only money they handle.
class WorkerOrder {
  const WorkerOrder({
    required this.orderId,
    required this.orderNumber,
    this.orderStatus,
    this.deliveryStatus,
    this.deliveryId,
    this.allowedNext = const [],
    this.customerName,
    this.customerPhone,
    this.deliveryAddress,
    this.landmark,
    this.deliveryInstructions,
    this.latitude,
    this.longitude,
    this.totalItems = 0,
    this.items = const [],
    this.amountToCollect,
    this.cashOnDelivery = false,
    this.packedAt,
    this.packedBy,
  });

  final int orderId;
  final String orderNumber;
  final String? orderStatus;
  final String? deliveryStatus;

  /// Null when no delivery row exists yet - the order was packed before
  /// anybody was assigned to carry it. The screen shows the packing list and
  /// no status buttons, which is the truth of that situation.
  final int? deliveryId;

  /// The statuses this delivery may move to next, decided by the server.
  ///
  /// THE APP KNOWS NO RULES. It draws one button per entry here. That is why
  /// a phone running an old build cannot offer a move that has since been
  /// removed, and why no worker is ever shown a button the server refuses.
  final List<String> allowedNext;

  final String? customerName;
  final String? customerPhone;
  final String? deliveryAddress;

  /// "Near Gupta Medical Store", as its own line.
  ///
  /// The server used to glue this into [deliveryAddress]. It is the line that
  /// actually finds a house in a colony where the numbering restarts twice,
  /// and a rider reading one run-on string at arm's length loses it.
  final String? landmark;

  /// "Enter from the lane beside the medical store."
  final String? deliveryInstructions;

  /// THE DESTINATION, and it is a snapshot taken when the order was placed -
  /// not wherever the customer's saved address points today. See
  /// Order.captureDeliverySnapshot on the server for why those differ.
  final double? latitude;
  final double? longitude;

  /// True when there is somewhere for Navigate to open.
  bool get hasDestination => latitude != null && longitude != null;

  final int totalItems;
  final List<WorkerOrderLine> items;

  /// Cash to take at the door. Null or zero for anything already paid.
  final num? amountToCollect;
  final bool cashOnDelivery;

  final DateTime? packedAt;
  final String? packedBy;

  factory WorkerOrder.fromJson(Map<String, dynamic> json) => WorkerOrder(
        orderId: (json['orderId'] as num).toInt(),
        orderNumber: (json['orderNumber'] ?? '') as String,
        orderStatus: json['orderStatus'] as String?,
        deliveryStatus: json['deliveryStatus'] as String?,
        deliveryId: (json['deliveryId'] as num?)?.toInt(),
        allowedNext: (json['allowedNext'] as List?)
                ?.map((e) => e.toString())
                .toList(growable: false) ??
            const [],
        customerName: json['customerName'] as String?,
        customerPhone: json['customerPhone'] as String?,
        deliveryAddress: json['deliveryAddress'] as String?,
        landmark: json['landmark'] as String?,
        deliveryInstructions: json['deliveryInstructions'] as String?,
        latitude: (json['latitude'] as num?)?.toDouble(),
        longitude: (json['longitude'] as num?)?.toDouble(),
        totalItems: (json['totalItems'] as num?)?.toInt() ?? 0,
        items: (json['items'] as List?)
                ?.map((e) => WorkerOrderLine.fromJson(
                    Map<String, dynamic>.from(e as Map)))
                .toList(growable: false) ??
            const [],
        amountToCollect: json['amountToCollect'] as num?,
        cashOnDelivery: json['cashOnDelivery'] == true,
        packedAt: json['packedAt'] == null
            ? null
            : DateTime.tryParse(json['packedAt'].toString()),
        packedBy: json['packedBy'] as String?,
      );
}

/// One line on the packing list: what it is, what size, how many.
class WorkerOrderLine {
  const WorkerOrderLine(
      {required this.name, this.pack, required this.quantity});

  final String name;

  /// "500 g", "1 kg" - what is printed on the packet, which is how a worker
  /// tells two shelf-neighbours apart.
  final String? pack;
  final int quantity;

  factory WorkerOrderLine.fromJson(Map<String, dynamic> json) =>
      WorkerOrderLine(
        name: (json['name'] ?? 'Item') as String,
        pack: json['pack'] as String?,
        quantity: (json['quantity'] as num?)?.toInt() ?? 0,
      );
}

/// One active delivery on the home screen.
///
/// Deliberately not a [WorkerOrder]: a LIST does not need a packing list, and
/// sending one per row would multiply the payload on the screen that opens
/// most often, on the worst connection.
class WorkerTask {
  const WorkerTask({
    required this.deliveryId,
    required this.orderId,
    required this.orderNumber,
    required this.deliveryStatus,
    this.allowedNext = const [],
    this.customerName,
    this.deliveryAddress,
  });

  final int deliveryId;
  final int orderId;
  final String orderNumber;
  final String deliveryStatus;
  final List<String> allowedNext;
  final String? customerName;
  final String? deliveryAddress;

  factory WorkerTask.fromJson(Map<String, dynamic> json) => WorkerTask(
        deliveryId: (json['deliveryId'] as num).toInt(),
        orderId: (json['orderId'] as num?)?.toInt() ?? 0,
        orderNumber: (json['orderNumber'] ?? '') as String,
        deliveryStatus: (json['deliveryStatus'] ?? 'ASSIGNED') as String,
        allowedNext: (json['allowedNext'] as List?)
                ?.map((e) => e.toString())
                .toList(growable: false) ??
            const [],
        customerName: json['customerName'] as String?,
        deliveryAddress: json['deliveryAddress'] as String?,
      );
}

/// "OUT_FOR_DELIVERY" -> "Out for delivery".
///
/// Workers are not reading an enum. This lived as a private helper on the
/// order screen's button labels while the status LINE on the same screen, and
/// the task tiles on the home screen, printed the raw constant - so one screen
/// showed a worker both spellings of the same word at once.
String humanizeStatus(String status) {
  if (status.isEmpty) return status;
  final words = status.split('_').where((w) => w.isNotEmpty);
  if (words.isEmpty) return status;
  return words
      .map((w) => w[0].toUpperCase() + w.substring(1).toLowerCase())
      .join(' ');
}

/// Rupees, as money rather than as a Dart number.
///
/// [WorkerOrder.amountToCollect] is a `num`, so string interpolation renders a
/// whole-rupee JSON value of 450.0 as "450.0" - and this is the cash-collection
/// figure, the single number in this app that a worker counts into their hand
/// at a customer's door. Whole amounts print whole; paise print with two
/// digits.
String formatRupees(num amount) {
  final rounded = (amount * 100).round() / 100;
  if (rounded == rounded.roundToDouble()) {
    return '\u20B9${rounded.toInt()}';
  }
  return '\u20B9${rounded.toStringAsFixed(2)}';
}
