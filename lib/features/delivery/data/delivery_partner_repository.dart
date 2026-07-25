import '../../../core/api/api_client.dart';
import '../../admin/domain/delivery_partner_models.dart';
import '../domain/delivery_assignment_model.dart';

class DeliveryPartnerRepository {
  DeliveryPartnerRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<DeliveryAssignment>> getMyAssignments() async {
    final response = await apiClient.dio.get('/api/deliveries/my-assignments');
    return (response.data as List).map((e) => DeliveryAssignment.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Real current status - needed so the availability toggle reflects
  /// what's actually saved server-side, not a guessed initial value.
  Future<DeliveryPartnerModel> getMyProfile() async {
    final response = await apiClient.dio.get('/api/delivery-partners/me');
    return DeliveryPartnerModel.fromJson(response.data as Map<String, dynamic>);
  }

  /// Ownership is enforced server-side - this will fail with a "not found"
  /// if the delivery isn't actually assigned to the caller, not just to
  /// anyone with the DELIVERY_BOY role.
  Future<void> updateStatus({required int deliveryId, required String status}) async {
    await apiClient.dio.put(
      '/api/deliveries/$deliveryId/status',
      queryParameters: {'status': status},
    );
  }

  /// Self-service - only ever touches the caller's own record, resolved
  /// server-side from their account. Can never affect another partner's
  /// availability.
  Future<void> setMyAvailability(bool available) async {
    await apiClient.dio.put(
      '/api/delivery-partners/me/availability',
      queryParameters: {'available': available},
    );
  }
}
