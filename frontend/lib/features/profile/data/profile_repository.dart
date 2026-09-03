import 'package:image_picker/image_picker.dart';

import '../../../core/api/api_client.dart';
import '../../../core/images/image_upload_service.dart';
import '../domain/profile_models.dart';

class ProfileRepository {
  ProfileRepository({required this.apiClient})
      // Constructed here rather than injected, matching
      // AdminProductsRepository: the upload service is a thin wrapper over
      // this same apiClient, so passing it in separately would only create a
      // way for the two to end up pointing at different clients.
      : _uploads = ImageUploadService(apiClient: apiClient);

  final ApiClient apiClient;
  final ImageUploadService _uploads;

  /// Report how long the app was in the foreground, in seconds.
  ///
  /// The account comes from the token, never from this body - the client
  /// cannot file time against somebody else. The server caps the figure and
  /// answers with what it actually recorded, which may be less than what was
  /// sent or zero; nothing in the app changes either way.
  ///
  /// Errors are swallowed on purpose. This is telemetry: a customer must
  /// never see their app misbehave because a number about them failed to
  /// upload, and it must never trigger the token-refresh path on its way out
  /// the door.
  Future<void> reportAppSession(int seconds) async {
    try {
      await apiClient.dio.post(
        '/api/customers/me/app-session',
        data: {'seconds': seconds},
      );
    } catch (_) {
      // Deliberately ignored - see above.
    }
  }

  Future<Profile> getMyProfile() async {
    final response = await apiClient.dio.get('/api/customers/me');
    return Profile.fromJson(response.data as Map<String, dynamic>);
  }

  /// email is add-only, matching the backend exactly: it's only actually
  /// applied server-side if the account currently has none. Sending it
  /// when an email already exists is harmless - the backend just ignores it.
  Future<Profile> updateProfile({
    required String fullName,
    required String mobileNumber,
    String? email,
    String? currentPassword,
  }) async {
    final response = await apiClient.dio.put(
      '/api/customers/me',
      data: {
        'fullName': fullName,
        'mobileNumber': mobileNumber,
        'email': email,
        if (currentPassword != null && currentPassword.isNotEmpty) 'currentPassword': currentPassword,
      },
    );
    return Profile.fromJson(response.data as Map<String, dynamic>);
  }

  /// Longest edge of a stored avatar.
  ///
  /// It is displayed at about 96 logical pixels. 512 is four times that, so it
  /// still looks sharp if it is ever opened larger, and it turns a 4 MB camera
  /// original into something well under the 2 MB cap - which matters because
  /// the customer is uploading on mobile data, not shop wifi.
  static const double _maxAvatarEdge = 512;

  /// Below about 75 JPEG artefacts show on faces; 85 is the usual "cannot see
  /// the difference" point.
  static const int _avatarQuality = 85;

  /// Picks a photo, uploads it, and attaches it to the account.
  ///
  /// Returns null when the customer backs out of the picker - which must not
  /// be reported as a failure, because they did not do anything wrong.
  Future<Profile?> pickAndSetProfilePhoto({
    ImageSource source = ImageSource.gallery,
  }) async {
    final picked = await ImagePicker().pickImage(
      source: source,
      imageQuality: _avatarQuality,
      maxWidth: _maxAvatarEdge,
      maxHeight: _maxAvatarEdge,
    );
    if (picked == null) return null;

    final bytes = await picked.readAsBytes();
    final objectKey = await _uploads.uploadProfilePhoto(bytes: bytes);

    // Two steps because the bytes go straight to storage, not through the
    // API. This is the call that actually changes the account, and the server
    // verifies the key belongs to this customer before honouring it.
    final response = await apiClient.dio.put(
      '/api/customers/me/photo',
      data: {'objectKey': objectKey},
    );
    return Profile.fromJson(response.data as Map<String, dynamic>);
  }

  Future<Profile> removeProfilePhoto() async {
    final response = await apiClient.dio.delete('/api/customers/me/photo');
    return Profile.fromJson(response.data as Map<String, dynamic>);
  }

  /// Requires the current password. Typing DELETE in the UI is not enough.
  Future<void> deleteAccount({required String currentPassword}) async {
    await apiClient.dio.delete(
      '/api/customers/me',
      data: {'currentPassword': currentPassword},
    );
  }
}
