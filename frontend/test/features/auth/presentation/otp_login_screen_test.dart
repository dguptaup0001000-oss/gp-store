import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/storage/token_storage.dart';
import 'package:gpstore/core/util/app_haptics.dart';
import 'package:gpstore/features/auth/data/auth_repository.dart';
import 'package:gpstore/features/auth/domain/auth_models.dart';
import 'package:gpstore/features/auth/domain/otp_user_messages.dart';
import 'package:gpstore/features/auth/presentation/auth_providers.dart';
import 'package:gpstore/features/auth/presentation/forgot_password_screen.dart';
import 'package:gpstore/features/auth/presentation/login_screen.dart';
import 'package:gpstore/features/auth/presentation/otp_login_screen.dart';
import 'package:gpstore/features/auth/presentation/otp_providers.dart';

import '../../../support/test_api_client.dart';

class _FakeAuthRepository extends AuthRepository {
  _FakeAuthRepository()
      : super(
          apiClient: buildTestApiClient(FakeHttpClientAdapter()),
          tokenStorage: TokenStorage(),
        );

  Object? requestError;
  Object? verifyError;
  Object? resetRequestError;
  Object? resetVerifyError;
  Object? resetCompleteError;
  Completer<void>? holdRequest;
  int loginOtpRequests = 0;
  int loginOtpVerifies = 0;
  int resetRequests = 0;
  String? lastPhone;

  @override
  Future<void> requestLoginOtp({required String phone}) async {
    lastPhone = phone;
    loginOtpRequests++;
    if (holdRequest != null) await holdRequest!.future;
    if (requestError != null) throw requestError!;
  }

  @override
  Future<AuthResponse> verifyLoginOtp({required String phone, required String otp}) async {
    loginOtpVerifies++;
    if (verifyError != null) throw verifyError!;
    await tokenStorage.saveTokens(accessToken: 'access', refreshToken: 'refresh');
    return const AuthResponse(
      token: 'access',
      refreshToken: 'refresh',
      customerId: 1,
      role: 'CUSTOMER',
    );
  }

  @override
  Future<void> requestPasswordResetOtp({required String phone}) async {
    lastPhone = phone;
    resetRequests++;
    if (resetRequestError != null) throw resetRequestError!;
  }

  @override
  Future<String> verifyPasswordResetOtp({required String phone, required String otp}) async {
    if (resetVerifyError != null) throw resetVerifyError!;
    return 'reset-token';
  }

  @override
  Future<void> completePasswordReset({required String resetToken, required String newPassword}) async {
    if (resetCompleteError != null) throw resetCompleteError!;
  }

  @override
  Future<AuthResponse> login({required String email, required String password, bool rememberMe = true}) async {
    await tokenStorage.saveTokens(accessToken: 'pw-access', refreshToken: 'pw-refresh');
    return AuthResponse(
      token: 'pw-access',
      refreshToken: 'pw-refresh',
      customerId: 2,
      email: email,
      role: 'CUSTOMER',
    );
  }
}

void main() {
  late _FakeAuthRepository repo;

  setUpAll(setUpFakeSecureStorage);

  setUp(() {
    AppHaptics.resetForTest();
    AppHaptics.enabled = false;
    repo = _FakeAuthRepository();
  });

  Widget otpApp() {
    return ProviderScope(
      overrides: [authRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: OtpLoginScreen()),
    );
  }

  testWidgets('password login remains available next to OTP', (tester) async {
    tester.view.physicalSize = const Size(800, 1400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    final router = GoRouter(
      initialLocation: '/login',
      routes: [
        GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
        GoRoute(path: '/login/otp', builder: (_, __) => const SizedBox()),
        GoRoute(path: '/login/forgot', builder: (_, __) => const SizedBox()),
        GoRoute(path: '/register', builder: (_, __) => const SizedBox()),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [authRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pump();

    expect(find.text('Log in'), findsOneWidget);
    expect(find.text('Login with Mobile OTP'), findsOneWidget);
    expect(find.text('Forgot password?'), findsOneWidget);
  });

  testWidgets('invalid phone is rejected before a network call', (tester) async {
    await tester.pumpWidget(otpApp());
    await tester.enterText(find.byType(TextFormField), '12345');
    await tester.tap(find.text('Continue'));
    await tester.pump();
    expect(find.text('Please enter a valid Indian mobile number.'), findsOneWidget);
    expect(repo.loginOtpRequests, 0);
  });

  testWidgets('requesting an OTP shows loading then the masked number', (tester) async {
    repo.holdRequest = Completer<void>();
    await tester.pumpWidget(otpApp());
    await tester.enterText(find.byType(TextFormField), '9876543210');
    await tester.tap(find.text('Continue'));
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    repo.holdRequest!.complete();
    await tester.pump();
    expect(find.text('Enter the OTP sent to ******3210'), findsOneWidget);
    expect(find.textContaining('Resend code in'), findsOneWidget);
    expect(repo.loginOtpRequests, 1);
    expect(repo.lastPhone, '9876543210');
  });

  testWidgets('wrong OTP shows an error and does not log the user in', (tester) async {
    repo.verifyError = ApiException(statusCode: 400, message: 'Invalid or expired OTP');
    await tester.pumpWidget(otpApp());
    await tester.enterText(find.byType(TextFormField), '9876543210');
    await tester.tap(find.text('Continue'));
    await tester.pump();

    await tester.enterText(find.byType(TextFormField), '000000');
    await tester.pump();
    expect(find.text(OtpUserMessages.wrongOtp), findsWidgets);
    expect(repo.loginOtpVerifies, 1);
  });

  testWidgets('network failure on request is actionable', (tester) async {
    repo.requestError = ApiException(statusCode: null, message: 'You appear to be offline. Check your connection and try again.');
    await tester.pumpWidget(otpApp());
    await tester.enterText(find.byType(TextFormField), '9876543210');
    await tester.tap(find.text('Continue'));
    await tester.pump();
    expect(find.textContaining('offline'), findsWidgets);
    expect(find.text('Continue'), findsOneWidget);
  });

  testWidgets('resend is held until the cooldown elapses', (tester) async {
    await tester.pumpWidget(otpApp());
    await tester.enterText(find.byType(TextFormField), '9876543210');
    await tester.tap(find.text('Continue'));
    await tester.pump();
    expect(find.text('Resend OTP'), findsNothing);

    await tester.pump(OtpFlowController.resendCooldown);
    await tester.pump();
    expect(find.text('Resend OTP'), findsOneWidget);

    await tester.tap(find.text('Resend OTP'));
    await tester.pump();
    expect(repo.loginOtpRequests, 2);
  });

  testWidgets('forgot password mismatch is caught before complete', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [authRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: ForgotPasswordScreen()),
    ));

    await tester.enterText(find.byType(TextField), '9876543210');
    await tester.tap(find.text('Send OTP'));
    await tester.pump();
    expect(find.text('Enter the OTP sent to ******3210'), findsOneWidget);
    expect(repo.resetRequests, 1);

    await tester.enterText(find.byType(TextField), '123456');
    await tester.pump();
    expect(find.text('Choose a new password'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextField, 'New password'), 'BrandNewPass9');
    await tester.enterText(find.widgetWithText(TextField, 'Confirm password'), 'Different1');
    await tester.tap(find.text('Reset password'));
    await tester.pump();
    expect(find.text(OtpUserMessages.passwordMismatch), findsWidgets);
  });
}
