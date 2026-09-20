/// The Super Admin directory: what a search returns, and what a tap opens.
///
/// EVERY `fromJson` HERE TOLERATES A MISSING FIELD, and that is not laziness.
/// The marketplace work shipped a backend whose tests all passed while the app
/// drew the wrong button on every card, because a derived accessor never
/// reached the wire and the model's safe default absorbed it silently. The
/// lesson taken was not "stop defaulting" - a null-hostile model would crash
/// the screen instead - it was that the CONTRACT needs a test of its own. So
/// these defaults exist, and a wire-format test asserts the backend actually
/// sends each key.
library;

/// One merchant in the Merchants tab.
class MerchantHit {
  const MerchantHit({
    required this.id,
    required this.merchantRef,
    this.displayName,
    this.legalName,
    this.ownerName,
    this.email,
    this.phone,
    this.status,
    this.active = true,
    this.shopCount = 0,
    this.createdAt,
  });

  final int id;
  final String merchantRef;
  final String? displayName;
  final String? legalName;

  /// The person behind the business. Shown because it is usually what tells
  /// two similarly-named merchants apart.
  final String? ownerName;
  final String? email;
  final String? phone;
  final String? status;
  final bool active;

  /// How many shops this merchant runs. From the same query as the row.
  final int shopCount;
  final DateTime? createdAt;

  /// What to put on the first line. Falls back through the names a merchant
  /// might have rather than showing an id to a human.
  String get title {
    for (final candidate in [displayName, legalName]) {
      if (candidate != null && candidate.trim().isNotEmpty) return candidate.trim();
    }
    return merchantRef;
  }

  String get shopsLabel => shopCount == 1 ? '1 shop' : '$shopCount shops';

  factory MerchantHit.fromJson(Map<String, dynamic> json) => MerchantHit(
        id: (json['id'] as num).toInt(),
        merchantRef: (json['merchantRef'] as String?) ?? 'M-${json['id']}',
        displayName: json['displayName'] as String?,
        legalName: json['legalName'] as String?,
        ownerName: json['ownerName'] as String?,
        email: json['email'] as String?,
        phone: json['phone'] as String?,
        status: json['status'] as String?,
        active: (json['active'] as bool?) ?? true,
        shopCount: (json['shopCount'] as num?)?.toInt() ?? 0,
        createdAt: _time(json['createdAt']),
      );
}

/// One customer in the Customers tab.
class CustomerHit {
  const CustomerHit({
    required this.id,
    required this.customerRef,
    this.name,
    this.email,
    this.phone,
    this.active = true,
    this.orderCount = 0,
    this.createdAt,
    this.lastOrderAt,
  });

  final int id;
  final String customerRef;
  final String? name;
  final String? email;
  final String? phone;
  final bool active;
  final int orderCount;
  final DateTime? createdAt;
  final DateTime? lastOrderAt;

  String get title =>
      (name != null && name!.trim().isNotEmpty) ? name!.trim() : customerRef;

  String get ordersLabel => orderCount == 1 ? '1 order' : '$orderCount orders';

  factory CustomerHit.fromJson(Map<String, dynamic> json) => CustomerHit(
        id: (json['id'] as num).toInt(),
        customerRef: (json['customerRef'] as String?) ?? 'C-${json['id']}',
        name: json['name'] as String?,
        email: json['email'] as String?,
        phone: json['phone'] as String?,
        active: (json['active'] as bool?) ?? true,
        orderCount: (json['orderCount'] as num?)?.toInt() ?? 0,
        createdAt: _time(json['createdAt']),
        lastOrderAt: _time(json['lastOrderAt']),
      );
}

/// A page of directory results, with the server's own total.
///
/// THE TOTAL IS THE SERVER'S. A screen that counted `content.length` would
/// say "20 results" for every search on a marketplace with ten thousand
/// customers, which is worse than saying nothing.
class DirectoryPage<T> {
  const DirectoryPage({
    required this.content,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
  });

  final List<T> content;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;

  bool get isEmpty => content.isEmpty;
  bool get hasMore => page + 1 < totalPages;

  static DirectoryPage<T> fromJson<T>(
      Map<String, dynamic> json, T Function(Map<String, dynamic>) item) {
    final raw = (json['content'] as List?) ?? const [];
    return DirectoryPage<T>(
      content: raw
          .whereType<Map>()
          .map((e) => item(Map<String, dynamic>.from(e)))
          .toList(growable: false),
      page: (json['page'] as num?)?.toInt() ?? 0,
      size: (json['size'] as num?)?.toInt() ?? 0,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
    );
  }

  static DirectoryPage<T> empty<T>() => DirectoryPage<T>(
      content: const [], page: 0, size: 0, totalElements: 0, totalPages: 0);
}

/// How a merchant has been trading, and how honest that measurement is.
///
/// [sessionsMeasured] is false for every merchant today and the screen says
/// so in the merchant's own words via [note], rather than drawing a zero.
/// GP-STORE does not time staff phones - a deliberate privacy decision - so
/// there is no stored active time to total, and estimating one from order
/// timestamps would be inventing history.
class MerchantActivity {
  const MerchantActivity({
    this.firstOrderAt,
    this.lastOrderAt,
    this.lastListingUpdateAt,
    this.lastAdminEventAt,
    this.activeDaysInWindow = 0,
    this.sessionsMeasured = false,
    this.note = '',
  });

  final DateTime? firstOrderAt;
  final DateTime? lastOrderAt;
  final DateTime? lastListingUpdateAt;
  final DateTime? lastAdminEventAt;
  final int activeDaysInWindow;
  final bool sessionsMeasured;
  final String note;

  /// The most recent thing this merchant demonstrably did.
  DateTime? get lastActivityAt {
    DateTime? latest;
    for (final candidate in [lastOrderAt, lastListingUpdateAt, lastAdminEventAt]) {
      if (candidate == null) continue;
      if (latest == null || candidate.isAfter(latest)) latest = candidate;
    }
    return latest;
  }

  factory MerchantActivity.fromJson(Map<String, dynamic> json) => MerchantActivity(
        firstOrderAt: _time(json['firstOrderAt']),
        lastOrderAt: _time(json['lastOrderAt']),
        lastListingUpdateAt: _time(json['lastListingUpdateAt']),
        lastAdminEventAt: _time(json['lastAdminEventAt']),
        activeDaysInWindow: (json['activeDaysInWindow'] as num?)?.toInt() ?? 0,
        sessionsMeasured: (json['sessionsMeasured'] as bool?) ?? false,
        note: (json['note'] as String?) ?? '',
      );
}

/// How a customer uses the app. Unlike a merchant, this is real - the
/// customer app does report sessions - but it is still client-reported and
/// server-capped, which [note] says.
class CustomerActivity {
  const CustomerActivity({
    this.firstSessionAt,
    this.lastSessionAt,
    this.sessions = 0,
    this.totalSeconds = 0,
    this.activeDays = 0,
    this.lastOrderAt,
    this.sessionsMeasured = false,
    this.note = '',
  });

  final DateTime? firstSessionAt;
  final DateTime? lastSessionAt;
  final int sessions;
  final int totalSeconds;
  final int activeDays;
  final DateTime? lastOrderAt;
  final bool sessionsMeasured;
  final String note;

  /// "4h 12m" - never a bare number of seconds on a human screen.
  String get totalTimeLabel {
    if (totalSeconds <= 0) return '—';
    final hours = totalSeconds ~/ 3600;
    final minutes = (totalSeconds % 3600) ~/ 60;
    if (hours == 0) return '${minutes}m';
    return minutes == 0 ? '${hours}h' : '${hours}h ${minutes}m';
  }

  factory CustomerActivity.fromJson(Map<String, dynamic> json) => CustomerActivity(
        firstSessionAt: _time(json['firstSessionAt']),
        lastSessionAt: _time(json['lastSessionAt']),
        sessions: (json['sessions'] as num?)?.toInt() ?? 0,
        totalSeconds: (json['totalSeconds'] as num?)?.toInt() ?? 0,
        activeDays: (json['activeDays'] as num?)?.toInt() ?? 0,
        lastOrderAt: _time(json['lastOrderAt']),
        sessionsMeasured: (json['sessionsMeasured'] as bool?) ?? false,
        note: (json['note'] as String?) ?? '',
      );
}

/// Where a customer actually buys.
///
/// [preferred] AND [orders] ARE DIFFERENT FACTS and this class keeps them
/// apart on purpose. Buying somewhere often may mean they like it, or only
/// that it is the shop that delivers to their street. Only [preferred] means
/// the customer said so.
class ShopAffinity {
  const ShopAffinity({
    required this.shopId,
    this.shopName,
    this.orders = 0,
    this.spent,
    this.lastOrderAt,
    this.preferred = false,
  });

  final int shopId;
  final String? shopName;
  final int orders;
  final num? spent;
  final DateTime? lastOrderAt;
  final bool preferred;

  String get title => shopName ?? 'S-$shopId';
  String get ordersLabel => orders == 1 ? '1 order' : '$orders orders';

  factory ShopAffinity.fromJson(Map<String, dynamic> json) => ShopAffinity(
        shopId: (json['shopId'] as num).toInt(),
        shopName: json['shopName'] as String?,
        orders: (json['orders'] as num?)?.toInt() ?? 0,
        spent: json['spent'] as num?,
        lastOrderAt: _time(json['lastOrderAt']),
        preferred: (json['preferred'] as bool?) ?? false,
      );
}

/// One administrative event, for the security section.
class AuditEntry {
  const AuditEntry({
    required this.id,
    this.action,
    this.actorEmail,
    this.actorRole,
    this.entityType,
    this.entityId,
    this.previousState,
    this.newState,
    this.reason,
    this.occurredAt,
  });

  final int id;
  final String? action;
  final String? actorEmail;
  final String? actorRole;
  final String? entityType;
  final int? entityId;
  final String? previousState;
  final String? newState;
  final String? reason;
  final DateTime? occurredAt;

  /// "DRAFT → ACTIVE", or null when the event was not a transition.
  String? get transition {
    if (previousState == null && newState == null) return null;
    return '${previousState ?? '—'} → ${newState ?? '—'}';
  }

  factory AuditEntry.fromJson(Map<String, dynamic> json) => AuditEntry(
        id: (json['id'] as num).toInt(),
        action: json['action'] as String?,
        actorEmail: json['actorEmail'] as String?,
        actorRole: json['actorRole'] as String?,
        entityType: json['entityType'] as String?,
        entityId: (json['entityId'] as num?)?.toInt(),
        previousState: json['previousState'] as String?,
        newState: json['newState'] as String?,
        reason: json['reason'] as String?,
        occurredAt: _time(json['occurredAt']),
      );
}

DateTime? _time(Object? raw) {
  if (raw is! String || raw.isEmpty) return null;
  return DateTime.tryParse(raw);
}
