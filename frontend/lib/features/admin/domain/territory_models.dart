import 'dart:convert';

import 'package:freezed_annotation/freezed_annotation.dart';

part 'territory_models.freezed.dart';
part 'territory_models.g.dart';

/// One of the eight main zones. Zones carry no geometry; shape is the union
/// of their territories.
@freezed
class TerritoryZone with _$TerritoryZone {
  const factory TerritoryZone({
    int? id,
    required String code,
    required String name,
    String? notes,
    int? displayOrder,
    @Default(true) bool active,
  }) = _TerritoryZone;

  factory TerritoryZone.fromJson(Map<String, dynamic> json) => _$TerritoryZoneFromJson(json);
}

/// Nested rider on a territory row. Only the fields the list needs to show.
@freezed
class TerritoryPartnerRef with _$TerritoryPartnerRef {
  const factory TerritoryPartnerRef({
    int? id,
    String? name,
    String? mobile,
    String? vehicleType,
  }) = _TerritoryPartnerRef;

  factory TerritoryPartnerRef.fromJson(Map<String, dynamic> json) => _$TerritoryPartnerRefFromJson(json);
}

/// One of the 26 permanent delivery territories (a subzone).
///
/// [boundary] is the stored JSON text of `[lat, lng]` pairs. This model never
/// turns that text into a drawn map — Phase 2b only reports whether an
/// outline exists.
@freezed
class TerritorySubzone with _$TerritorySubzone {
  const factory TerritorySubzone({
    int? id,
    required String code,
    required String name,
    String? boundary,
    TerritoryPartnerRef? primaryPartner,
    @Default(12) int maxConcurrentOrders,
    String? notes,
    int? displayOrder,
    @Default(true) bool active,
    TerritoryZone? zone,
  }) = _TerritorySubzone;

  const TerritorySubzone._();

  factory TerritorySubzone.fromJson(Map<String, dynamic> json) => _$TerritorySubzoneFromJson(json);

  BoundaryPresence get boundaryPresence => describeBoundary(boundary);
}

/// GET /api/admin/territory/health
@freezed
class TerritoryHealth with _$TerritoryHealth {
  const factory TerritoryHealth({
    @Default(0) int zones,
    @Default(8) int expectedZones,
    @Default(0) int subzones,
    @Default(26) int expectedSubzones,
    @Default(0) int subzonesWithBoundary,
    @Default(0) int subzonesWithPrimaryPartner,
    @Default(<String>[]) List<String> subzonesMissingBoundary,
    @Default(<String>[]) List<String> subzonesMissingPartner,
    @Default(<String>[]) List<String> subzonesWithNoNeighbours,
    @Default(<String>[]) List<String> problems,
  }) = _TerritoryHealth;

  const TerritoryHealth._();

  factory TerritoryHealth.fromJson(Map<String, dynamic> json) => _$TerritoryHealthFromJson(json);

  bool get hasProblems => problems.isNotEmpty;
}

/// GET /api/admin/territory/resolve
@freezed
class TerritoryResolveResult with _$TerritoryResolveResult {
  const factory TerritoryResolveResult({
    @Default('') String subzoneCode,
    @Default(<String>[]) List<String> matches,
    @Default(false) bool overlapping,
    @Default(0) int mappedTerritories,
  }) = _TerritoryResolveResult;

  factory TerritoryResolveResult.fromJson(Map<String, dynamic> json) =>
      _$TerritoryResolveResultFromJson(json);
}

enum BoundaryPresence { missing, unreadable, stored }

/// Status of a stored outline, without rendering it.
BoundaryPresence describeBoundary(String? boundary) {
  if (boundary == null || boundary.trim().isEmpty) return BoundaryPresence.missing;
  try {
    final decoded = jsonDecode(boundary);
    if (decoded is List && decoded.length >= 3) return BoundaryPresence.stored;
    return BoundaryPresence.unreadable;
  } catch (_) {
    return BoundaryPresence.unreadable;
  }
}

int? boundaryVertexCount(String? boundary) {
  if (describeBoundary(boundary) != BoundaryPresence.stored) return null;
  final decoded = jsonDecode(boundary!) as List<dynamic>;
  return decoded.length;
}
