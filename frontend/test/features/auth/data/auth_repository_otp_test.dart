import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/storage/token_storage.dart';
import 'package:gpstore/features/auth/data/auth_repository.dart';

import '../../../support/test_api_client.dart';

void main() {
  late FakeHttpClientAdapter adapter;
  late AuthRepository repository;
  late TokenStorage tokenStorage;

  setUp(() {
    setUpFakeSecureStorage();
    adapter = FakeHttpClientAdapter();
    tokenStorage = TokenStorage();
    final client = ApiClient(tokenStorage: tokenStorage);
    client.dio.httpClientAdapter = adapter;
    repository = AuthRepository(apiClient: client, tokenStorage: tokenStorage);
  });

  test('login OTP request posts phone to the Part 1 endpoint', () async {
    Map<String, dynamic>? body;
    adapter.on('POST', '/api/auth/otp/login/request', (options) {
      body = Map<String, dynamic>.from(options.data as Map);
      return const FakeResponse({'message': 'If this number is eligible, an OTP has been sent.'});
    });

    await repository.requestLoginOtp(phone: '9876543210');
    expect(body, {'phone': '9876543210'});
  });

  test('login OTP verify stores the existing JWT pair', () async {
    Map<String, dynamic>? body;
    adapter.on('POST', '/api/auth/otp/login/verify', (options) {
      body = Map<String, dynamic>.from(options.data as Map);
      return const FakeResponse({
        'token': 'access-1',
        'refreshToken': 'refresh-1',
        'customerId': 9,
        'email': null,
        'role': 'CUSTOMER',
      });
    });

    final auth = await repository.verifyLoginOtp(phone: '9876543210', otp: '654321');
    expect(body, {'phone': '9876543210', 'otp': '654321'});
    expect(auth.token, 'access-1');
    expect(await tokenStorage.getAccessToken(), 'access-1');
    expect(await tokenStorage.getRefreshToken(), 'refresh-1');
  });

  test('password reset request never claims the number is unregistered', () async {
    adapter.on('POST', '/api/auth/password-reset/request', (options) {
      return const FakeResponse({'message': 'If this number is eligible, an OTP has been sent.'});
    });
    await repository.requestPasswordResetOtp(phone: '9123456789');
  });

  test('password reset verify returns a reset token that is not persisted', () async {
    adapter.on('POST', '/api/auth/password-reset/verify', (options) {
      return const FakeResponse({'reset_token': 'reset-abc', 'expires_in_seconds': 600});
    });

    final token = await repository.verifyPasswordResetOtp(phone: '9876543210', otp: '111111');
    expect(token, 'reset-abc');
    expect(await tokenStorage.getAccessToken(), isNull);
    expect(await tokenStorage.getRefreshToken(), isNull);
  });

  test('password reset complete sends reset_token and new_password', () async {
    Map<String, dynamic>? body;
    adapter.on('POST', '/api/auth/password-reset/complete', (options) {
      body = Map<String, dynamic>.from(options.data as Map);
      return const FakeResponse({'message': 'Password reset - you can now log in with your new password'});
    });

    await repository.completePasswordReset(resetToken: 'reset-abc', newPassword: 'BrandNewPass9');
    expect(body, {'reset_token': 'reset-abc', 'new_password': 'BrandNewPass9'});
  });

  test('existing password login still posts to /api/auth/login', () async {
    adapter.on('POST', '/api/auth/login', (options) {
      final data = Map<String, dynamic>.from(options.data as Map);
      expect(data['email'], 'a@b.com');
      expect(data['password'], 'Passw0rd!');
      return const FakeResponse({
        'token': 'access-pw',
        'refreshToken': 'refresh-pw',
        'customerId': 1,
        'email': 'a@b.com',
        'role': 'CUSTOMER',
      });
    });

    final auth = await repository.login(email: 'a@b.com', password: 'Passw0rd!');
    expect(auth.token, 'access-pw');
    expect(await tokenStorage.getAccessToken(), 'access-pw');
  });
}
