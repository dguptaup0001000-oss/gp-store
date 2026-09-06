import '../api/api_client.dart';
import 'marketplace_models.dart';

/// Reads the public marketplace surface.
///
/// EVERY DECISION HERE IS THE BACKEND'S. Which shops serve a point, how far
/// each is, whether a shop may be shown to customers at all - all of it is
/// answered by `/api/marketplace/**` and none of it is recomputed in Dart.
/// This class parses and nothing else, on purpose: a distance calculated in
/// the app and a distance calculated on the server would disagree the first
/// time either changed, and the server's is the one that decided whether the
/// shop was offered.
class MarketplaceRepository {
  MarketplaceRepository({required this.apiClient});

  final ApiClient apiClient;

  /// Shops that will deliver to this point, nearest first.
  ///
  /// EMPTY IS AN ANSWER, NOT A FAILURE. Nobody delivering to a point is an
  /// ordinary state - a customer outside every shop's radius - and the screen
  /// says so rather than showing an error. A missing pin returns empty for
  /// the same reason: an address that cannot be proved deliverable is not
  /// deliverable, which is the rule the backend already applies.
  Future<List<Storefront>> shopsNear({double? latitude, double? longitude}) async {
    final response = await apiClient.dio.get(
      '/api/marketplace/shops',
      queryParameters: {
        if (latitude != null) 'lat': latitude,
        if (longitude != null) 'lng': longitude,
      },
    );
    final data = response.data;
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((e) => Storefront.fromJson(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }

  /// One storefront. Throws ApiException(404) when the marketplace does not
  /// show it - a draft, closed or suspended shop is indistinguishable from a
  /// shop that never existed, which is the backend's choice and not this
  /// app's to undo.
  Future<Storefront> storefront(int shopId) async {
    final response = await apiClient.dio.get('/api/marketplace/shops/$shopId');
    return Storefront.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  Future<MarketplaceMode> mode() async {
    final response = await apiClient.dio.get('/api/marketplace/mode');
    return MarketplaceMode.fromJson(Map<String, dynamic>.from(response.data as Map));
  }
}
