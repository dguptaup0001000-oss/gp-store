import '../../../core/api/api_client.dart';
import '../../../core/storage/token_storage.dart';
import '../domain/auth_models.dart';

class AuthRepository {
  AuthRepository({required this.apiClient, required this.tokenStorage});

  final ApiClient apiClient;
  final TokenStorage tokenStorage;

  Future<AuthResponse> register({
    required String name,
    required String email,
    required String phone,
    required String password,
  }) async {
    final response = await apiClient.dio.post(
      '/api/auth/register',
      data: {
        'name': name,
        'email': email,
        'phone': phone,
        'password': password,
      },
    );

    final auth = AuthResponse.fromJson(response.data as Map<String, dynamic>);
    await tokenStorage.saveTokens(accessToken: auth.token, refreshToken: auth.refreshToken);
    return auth;
  }

  Future<AuthResponse> login({required String email, required String password, bool rememberMe = true}) async {
    final response = await apiClient.dio.post(
      '/api/auth/login',
      data: {'email': email, 'password': password},
    );

    final auth = AuthResponse.fromJson(response.data as Map<String, dynamic>);
    await tokenStorage.saveTokens(accessToken: auth.token, refreshToken: auth.refreshToken);
    await tokenStorage.setRememberMe(rememberMe);
    return auth;
  }

  /// Logs out of just this device - matches the backend's distinction
  /// between /logout (this session) and /logout-all (every session).
  Future<void> logout() async {
    final refreshToken = await tokenStorage.getRefreshToken();
    if (refreshToken != null) {
      try {
        await apiClient.dio.post('/api/auth/logout', data: {'refreshToken': refreshToken});
      } catch (_) {
        // Logout is best-effort server-side - the token gets cleared locally
        // regardless, so the user is logged out on THIS device either way,
        // even if the network call itself failed.
      }
    }
    await tokenStorage.clear();
  }

  /// Logs out of EVERY device/session, not just this one - requires a valid
  /// access token (unlike the other auth endpoints), matching the backend's
  /// SecurityConfig rule for /api/auth/logout-all specifically.
  Future<void> logoutAllDevices() async {
    try {
      await apiClient.dio.post('/api/auth/logout-all');
    } catch (_) {
      // Same reasoning as logout() - clear locally regardless of whether the
      // server call succeeded, so this device is signed out either way.
    }
    await tokenStorage.clear();
  }

  /// Sends a login OTP to this phone number - works whether it's a new or
  /// existing customer (the account itself is only created/found on verify).
  Future<void> sendOtp({required String mobileNumber}) async {
    await apiClient.dio.post('/api/auth/otp/send', data: {'mobileNumber': mobileNumber});
  }

  /// Verifies the OTP and logs in - the backend auto-creates a bare account
  /// if this phone number has never been seen before.
  Future<AuthResponse> verifyOtp({required String mobileNumber, required String otp}) async {
    final response = await apiClient.dio.post(
      '/api/auth/otp/verify',
      data: {'mobileNumber': mobileNumber, 'otp': otp},
    );

    final auth = AuthResponse.fromJson(response.data as Map<String, dynamic>);
    await tokenStorage.saveTokens(accessToken: auth.token, refreshToken: auth.refreshToken);
    return auth;
  }

  Future<bool> hasStoredSession() async {
    final refreshToken = await tokenStorage.getRefreshToken();
    return refreshToken != null;
  }

  /// Whether a stored session (see hasStoredSession) should actually survive
  /// an app cold start, per the "Remember me" choice made at login time.
  Future<bool> shouldRestoreSession() => tokenStorage.getRememberMe();

  /// currentPassword is genuinely optional - null/blank for an OTP-only
  /// account setting up password login for the first time, matching the
  /// backend's changePassword() behavior exactly.
  Future<void> changePassword({String? currentPassword, required String newPassword}) async {
    await apiClient.dio.put('/api/auth/change-password', data: {
      'currentPassword': currentPassword,
      'newPassword': newPassword,
    });
  }

  /// Forgot-password flow - proves identity via OTP to the account's own
  /// mobile number (there's no email infrastructure in this system). No
  /// auth needed - this IS the "I'm locked out" recovery path.
  Future<void> resetPasswordWithOtp({
    required String mobileNumber,
    required String otp,
    required String newPassword,
  }) async {
    await apiClient.dio.post('/api/auth/reset-password-with-otp', data: {
      'mobileNumber': mobileNumber,
      'otp': otp,
      'newPassword': newPassword,
    });
  }
}
