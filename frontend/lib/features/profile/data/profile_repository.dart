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

  /// Google Play Account Deletion Requirement - see CustomerService
  /// .deleteOwnAccount's doc comment on the backend for exactly what this
  /// does (anonymizes the account and ends every session immediately; does
  /// NOT erase order/invoice history, which has real tax retention
  /// requirements). The caller is responsible for clearing local token
  /// storage and navigating to login right after this succeeds - the
  /// backend has already revoked the tokens by the time this returns, so
  /// any further authenticated call in this app session would fail anyway.
  Future<void> deleteAccount() async {
    await apiClient.dio.delete('/api/customers/me');
  }
}
