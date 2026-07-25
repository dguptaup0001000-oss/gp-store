import '../../../core/api/api_client.dart';
import '../domain/profile_models.dart';

class ProfileRepository {
  ProfileRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<Profile> getMyProfile() async {
    final response = await apiClient.dio.get('/api/customers/me');
    return Profile.fromJson(response.data as Map<String, dynamic>);
  }

  /// email is add-only, matching the backend exactly: it's only actually
  /// applied server-side if the account currently has none. Sending it
  /// when an email already exists is harmless - the backend just ignores it.
  Future<Profile> updateProfile({required String fullName, required String mobileNumber, String? email}) async {
    final response = await apiClient.dio.put(
      '/api/customers/me',
      data: {'fullName': fullName, 'mobileNumber': mobileNumber, 'email': email},
    );
    return Profile.fromJson(response.data as Map<String, dynamic>);
  }
}
