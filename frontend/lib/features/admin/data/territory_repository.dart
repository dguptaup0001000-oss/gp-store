import '../../../core/api/api_client.dart';
import '../domain/territory_models.dart';

/// Admin territory HTTP client. Display and persist configuration only —
/// no map drawing and no dispatch-gate logic.
class TerritoryRepository {
  TerritoryRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<TerritoryHealth> getHealth() async {
    final response = await apiClient.dio.get('/api/admin/territory/health');
    return TerritoryHealth.fromJson(response.data as Map<String, dynamic>);
  }

  Future<List<TerritoryZone>> listZones() async {
    final response = await apiClient.dio.get('/api/admin/territory/zones');
    return (response.data as List).map((e) => TerritoryZone.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<TerritoryZone> saveZone(TerritoryZone zone) async {
    final response = await apiClient.dio.post('/api/admin/territory/zones', data: zone.toJson());
    return TerritoryZone.fromJson(response.data as Map<String, dynamic>);
  }

  Future<List<TerritorySubzone>> listSubzones() async {
    final response = await apiClient.dio.get('/api/admin/territory/subzones');
    return (response.data as List)
        .map((e) => TerritorySubzone.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Creates or updates one territory under [zoneId]. Existing [boundary]
  /// text is sent back unchanged so an edit of name/capacity cannot blank
  /// an outline the server already stored.
  Future<TerritorySubzone> saveSubzone({required int zoneId, required TerritorySubzone subzone}) async {
    final payload = Map<String, dynamic>.from(subzone.toJson())
      ..remove('zone')
      ..remove('primaryPartner');
    final response = await apiClient.dio.post(
      '/api/admin/territory/zones/$zoneId/subzones',
      data: payload,
    );
    return TerritorySubzone.fromJson(response.data as Map<String, dynamic>);
  }

  Future<TerritorySubzone> setPrimaryPartner({required int subzoneId, int? partnerId}) async {
    final response = await apiClient.dio.put(
      '/api/admin/territory/subzones/$subzoneId/primary-partner',
      data: {'partnerId': partnerId},
    );
    return TerritorySubzone.fromJson(response.data as Map<String, dynamic>);
  }

  Future<TerritoryResolveResult> resolvePoint({required double latitude, required double longitude}) async {
    final response = await apiClient.dio.get(
      '/api/admin/territory/resolve',
      queryParameters: {'latitude': latitude, 'longitude': longitude},
    );
    return TerritoryResolveResult.fromJson(response.data as Map<String, dynamic>);
  }
}
