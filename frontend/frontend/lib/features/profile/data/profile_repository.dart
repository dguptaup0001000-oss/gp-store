import '../../../core/api/api_client.dart';
import '../domain/profile_models.dart';

class ProfileRepository {
  ProfileRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<Profile> getMyProfile() async {
    final response = await apiClient.dio.get('/api/customers/me');
    return Profile.fromJson(response.data as Map<String, dynamic>);
  }

  /// Deliberately narrow - mirrors the backend's own updateOwnProfile(),
  /// which only ever accepts fullName/mobileNumber. Email, password, and
  /// role are intentionally not editable through this endpoint.
  Future<Profile> updateProfile({required String fullName, required String mobileNumber}) async {
    final response = await apiClient.dio.put(
      '/api/customers/me',
      data: {'fullName': fullName, 'mobileNumber': mobileNumber},
    );
    return Profile.fromJson(response.data as Map<String, dynamic>);
  }
}
