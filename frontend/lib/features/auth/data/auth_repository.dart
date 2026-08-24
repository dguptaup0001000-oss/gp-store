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
    // BEFORE the session tokens go, because this call needs one.
    await _releaseThisDevicesPushToken();

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

  /// Detaches this phone's push token from the account being signed out.
  ///
  /// A device token identifies a PHONE, but the backend stores it against a
  /// customer. Sign out of the counter phone, hand it to someone else, and
  /// they sign in - the first account still holds that phone's token, so its
  /// order pushes keep arriving on a screen its owner no longer has. Those
  /// pushes carry a customer name and an order amount, so this is other
  /// people's information landing on the wrong device, not just a wasted
  /// send.
  ///
  /// Ordering is the whole point: this runs while the access token is still
  /// stored, because the endpoint is authenticated. Called from the two
  /// logout paths rather than from the push service's own stop(), which the
  /// auth listener only reaches after the session has already been cleared.
  ///
  /// Best-effort, like the logout call itself. Failing to reach the server
  /// must never leave someone unable to sign out of their own phone.
  Future<void> _releaseThisDevicesPushToken() async {
    try {
      await apiClient.dio.delete('/api/customers/me/fcm-token');
    } catch (_) {
      // Offline, or a session that had already expired. The next person to
      // sign in on this device overwrites the token anyway; this only
      // narrows the window.
    }
  }

  /// Logs out of EVERY device/session, not just this one - requires a valid
  /// access token (unlike the other auth endpoints), matching the backend's
  /// SecurityConfig rule for /api/auth/logout-all specifically.
  Future<void> logoutAllDevices() async {
    await _releaseThisDevicesPushToken();

    try {
      await apiClient.dio.post('/api/auth/logout-all');
    } catch (_) {
      // Same reasoning as logout() - clear locally regardless of whether the
      // server call succeeded, so this device is signed out either way.
    }
    await tokenStorage.clear();
  }

  /// Sends a login OTP. Uses the purpose-separated Part 1 endpoint so a
  /// LOGIN code cannot later be used to reset a password.
  Future<void> requestLoginOtp({required String phone}) async {
    await apiClient.dio.post('/api/auth/otp/login/request', data: {'phone': phone});
  }

  /// Verifies a LOGIN OTP and stores the existing JWT pair.
  Future<AuthResponse> verifyLoginOtp({required String phone, required String otp}) async {
    final response = await apiClient.dio.post(
      '/api/auth/otp/login/verify',
      data: {'phone': phone, 'otp': otp},
    );

    final auth = AuthResponse.fromJson(response.data as Map<String, dynamic>);
    await tokenStorage.saveTokens(accessToken: auth.token, refreshToken: auth.refreshToken);
    return auth;
  }

  Future<void> requestPasswordResetOtp({required String phone}) async {
    await apiClient.dio.post('/api/auth/password-reset/request', data: {'phone': phone});
  }

  /// Returns a short-lived reset token. This is not a session JWT and must
  /// not be written to secure storage or logs.
  Future<String> verifyPasswordResetOtp({required String phone, required String otp}) async {
    final response = await apiClient.dio.post(
      '/api/auth/password-reset/verify',
      data: {'phone': phone, 'otp': otp},
    );
    final body = response.data as Map<String, dynamic>;
    final token = body['reset_token'] as String? ?? body['resetToken'] as String?;
    if (token == null || token.isEmpty) {
      throw ApiException(statusCode: 400, message: 'Invalid or expired OTP');
    }
    return token;
  }

  Future<void> completePasswordReset({
    required String resetToken,
    required String newPassword,
  }) async {
    await apiClient.dio.post('/api/auth/password-reset/complete', data: {
      'reset_token': resetToken,
      'new_password': newPassword,
    });
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
}
