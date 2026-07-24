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
