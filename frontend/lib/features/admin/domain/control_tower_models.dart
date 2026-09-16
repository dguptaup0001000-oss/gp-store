class PlatformSearchResult {
  const PlatformSearchResult({
    required this.entityType,
    required this.entityId,
    required this.title,
    required this.reference,
    this.subtitle,
    this.maskedEmail,
    this.maskedPhone,
  });

  final String entityType;
  final int entityId;
  final String title;
  final String reference;
  final String? subtitle;
  final String? maskedEmail;
  final String? maskedPhone;

  factory PlatformSearchResult.fromJson(Map<String, dynamic> json) =>
      PlatformSearchResult(
        entityType: json['entityType']?.toString() ?? 'UNKNOWN',
        entityId: (json['entityId'] as num?)?.toInt() ?? 0,
        title: json['title']?.toString() ?? 'Unnamed record',
        reference: json['reference']?.toString() ?? '',
        subtitle: json['subtitle']?.toString(),
        maskedEmail: json['maskedEmail']?.toString(),
        maskedPhone: json['maskedPhone']?.toString(),
      );
}

class PlatformSearchPage {
  const PlatformSearchPage({
    required this.content,
    required this.page,
    required this.totalPages,
    required this.totalElements,
  });

  final List<PlatformSearchResult> content;
  final int page;
  final int totalPages;
  final int totalElements;

  bool get hasMore => page + 1 < totalPages;

  factory PlatformSearchPage.fromJson(Map<String, dynamic> json) {
    final raw = json['content'];
    return PlatformSearchPage(
      content: raw is List
          ? raw
              .whereType<Map>()
              .map((e) => PlatformSearchResult.fromJson(
                  Map<String, dynamic>.from(e)))
              .toList(growable: false)
          : const [],
      page: (json['page'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
    );
  }
}

class PlatformDashboardSummary {
  const PlatformDashboardSummary({
    required this.from,
    required this.to,
    required this.marketplace,
    required this.orderStatuses,
    required this.finance,
    required this.presenceAvailable,
    this.recentlyActiveAuthenticatedAccounts,
    this.presenceWindowSeconds,
  });

  final DateTime? from;
  final DateTime? to;
  final Map<String, dynamic> marketplace;
  final Map<String, int> orderStatuses;
  final Map<String, dynamic> finance;
  final bool presenceAvailable;
  final int? recentlyActiveAuthenticatedAccounts;
  final int? presenceWindowSeconds;

  int count(String key) => (marketplace[key] as num?)?.toInt() ?? 0;
  int orders(String key) => orderStatuses[key] ?? 0;
  String money(String key) => finance[key]?.toString() ?? '0.00';

  factory PlatformDashboardSummary.fromJson(Map<String, dynamic> json) {
    final rawStatuses = json['orderStatuses'];
    return PlatformDashboardSummary(
      from: DateTime.tryParse(json['from']?.toString() ?? ''),
      to: DateTime.tryParse(json['to']?.toString() ?? ''),
      marketplace: json['marketplace'] is Map
          ? Map<String, dynamic>.from(json['marketplace'] as Map)
          : const {},
      orderStatuses: rawStatuses is Map
          ? rawStatuses.map((key, value) =>
              MapEntry(key.toString(), (value as num?)?.toInt() ?? 0))
          : const {},
      finance: json['finance'] is Map
          ? Map<String, dynamic>.from(json['finance'] as Map)
          : const {},
      presenceAvailable: json['presenceAvailable'] == true,
      recentlyActiveAuthenticatedAccounts:
          (json['recentlyActiveAuthenticatedAccounts'] as num?)?.toInt(),
      presenceWindowSeconds:
          (json['presenceWindowSeconds'] as num?)?.toInt(),
    );
  }
}

class PlatformResourcePage {
  const PlatformResourcePage({
    required this.content,
    required this.page,
    required this.totalPages,
    required this.totalElements,
  });

  final List<Map<String, dynamic>> content;
  final int page;
  final int totalPages;
  final int totalElements;
  bool get hasMore => page + 1 < totalPages;

  factory PlatformResourcePage.fromJson(Map<String, dynamic> json) {
    final raw = json['content'];
    return PlatformResourcePage(
      content: raw is List
          ? raw.whereType<Map>().map((row) => Map<String, dynamic>.from(row)).toList()
          : const [],
      page: (json['page'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
    );
  }
}
