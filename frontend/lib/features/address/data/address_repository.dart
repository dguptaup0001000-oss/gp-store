import '../../../core/api/api_client.dart';
import '../domain/address_models.dart';

class AddressRepository {
  AddressRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<AddressModel>> getMyAddresses() async {
    final response = await apiClient.dio.get('/api/addresses/mine');
    return (response.data as List).map((e) => AddressModel.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<AddressModel> createAddress(AddressModel address) async {
    final response = await apiClient.dio.post('/api/addresses', data: address.toJson());
    return AddressModel.fromJson(response.data as Map<String, dynamic>);
  }

  /// Backend's updateAddress() copies every field from the request body -
  /// omitting one (like latitude/longitude) silently resets it, same class
  /// of risk as DeliveryPartner.update(). Always call this with a complete
  /// AddressModel (including id), never a partial one.
  Future<AddressModel> updateAddress(AddressModel address) async {
    assert(address.id != null, 'Cannot update an address without an id');
    final response = await apiClient.dio.put('/api/addresses/${address.id}', data: address.toJson());
    return AddressModel.fromJson(response.data as Map<String, dynamic>);
  }

  /// Fails with a clear, real reason (not a generic error) if this address
  /// is still referenced by a past order - see AddressService.deleteAddress
  /// on the backend for why that's a hard constraint, not just a soft rule.
  /// What the dropped pin probably means, for pre-filling the form.
  ///
  /// A SUGGESTION, and treated as one everywhere: the customer can overwrite
  /// every field it fills. An empty map is a normal answer - the geocoder may
  /// be unreachable, rate-limited, or simply have nothing for a village lane -
  /// and the form then behaves exactly as it does today.
  ///
  /// Never throws upward for that case. A failed convenience must not become
  /// an error banner on the screen where somebody is trying to save an address.
  Future<Map<String, String>> reverseGeocode({
    required double latitude,
    required double longitude,
  }) async {
    try {
      final response = await apiClient.dio.get(
        '/api/addresses/reverse-geocode',
        queryParameters: {'latitude': latitude, 'longitude': longitude},
      );
      final data = (response.data as Map?) ?? const {};
      return data.map((key, value) => MapEntry('$key', '$value'));
    } catch (_) {
      return const {};
    }
  }

  Future<void> deleteAddress(int addressId) async {
    await apiClient.dio.delete('/api/addresses/$addressId');
  }

  /// Returns whether this address can actually be delivered to, and the real
  /// distance - lets the UI warn BEFORE checkout rejects it.
  Future<({bool deliverable, double? distanceKm})> checkDeliverable(int addressId) async {
    final response = await apiClient.dio.get('/api/addresses/$addressId/deliverable');
    final data = response.data as Map<String, dynamic>;
    return (
      deliverable: data['deliverable'] as bool,
      distanceKm: (data['distanceKm'] as num?)?.toDouble(),
    );
  }
}
