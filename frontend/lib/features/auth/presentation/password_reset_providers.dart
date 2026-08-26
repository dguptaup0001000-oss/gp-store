import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../domain/indian_phone.dart';
import '../domain/otp_user_messages.dart';
import '../domain/password_policy.dart';
import 'auth_providers.dart';

enum PasswordResetStep {
  enteringPhone,
  sendingOtp,
  otpSent,
  verifyingOtp,
  settingPassword,
  completing,
  success,
}

class PasswordResetState {
  const PasswordResetState({
    this.step = PasswordResetStep.enteringPhone,
    this.mobileNumber,
    this.errorMessage,
    this.resendSecondsRemaining = 0,
  });

  final PasswordResetStep step;
  final String? mobileNumber;
  final String? errorMessage;
  final int resendSecondsRemaining;

  bool get canResend =>
      step == PasswordResetStep.otpSent && resendSecondsRemaining <= 0;

  PasswordResetState copyWith({
    PasswordResetStep? step,
    String? mobileNumber,
    String? errorMessage,
    int? resendSecondsRemaining,
  }) {
    return PasswordResetState(
      step: step ?? this.step,
      mobileNumber: mobileNumber ?? this.mobileNumber,
      errorMessage: errorMessage,
      resendSecondsRemaining: resendSecondsRemaining ?? this.resendSecondsRemaining,
    );
  }
}

/// Forgot-password flow. The short-lived reset token stays on this
/// controller only — never in [PasswordResetState], logs, or secure storage.
class PasswordResetController extends StateNotifier<PasswordResetState> {
  PasswordResetController(this._ref) : super(const PasswordResetState());

  final Ref _ref;
  DateTime? _sentAt;
  Timer? _countdown;
  String? _resetToken;

  static const resendCooldown = Duration(seconds: 45);
  static const otpLifetime = Duration(minutes: 5);

  Future<bool> requestOtp(String mobileNumber) async {
    final local = IndianPhone.toLocal10(mobileNumber);
    if (local == null) {
      state = state.copyWith(errorMessage: OtpUserMessages.invalidPhone);
      return false;
    }

    state = state.copyWith(
      step: PasswordResetStep.sendingOtp,
      mobileNumber: local,
      errorMessage: null,
    );

    try {
      await _ref.read(authRepositoryProvider).requestPasswordResetOtp(phone: local);
      _sentAt = DateTime.now();
      _resetToken = null;
      state = state.copyWith(
        step: PasswordResetStep.otpSent,
        resendSecondsRemaining: resendCooldown.inSeconds,
      );
      _startCountdown();
      HapticFeedback.lightImpact();
      return true;
    } catch (e) {
      state = state.copyWith(
        step: PasswordResetStep.enteringPhone,
        errorMessage: OtpUserMessages.fromError(e),
      );
      return false;
    }
  }

  Future<bool> resendOtp() async {
    final phone = state.mobileNumber;
    if (phone == null || !state.canResend) return false;
    return requestOtp(phone);
  }

  Future<bool> verifyOtp(String otp) async {
    if (state.mobileNumber == null) return false;
    state = state.copyWith(step: PasswordResetStep.verifyingOtp, errorMessage: null);

    try {
      _resetToken = await _ref.read(authRepositoryProvider).verifyPasswordResetOtp(
            phone: state.mobileNumber!,
            otp: otp,
          );
      _countdown?.cancel();
      state = state.copyWith(step: PasswordResetStep.settingPassword);
      HapticFeedback.lightImpact();
      return true;
    } catch (e) {
      final expired = _sentAt != null && DateTime.now().difference(_sentAt!) >= otpLifetime;
      state = state.copyWith(
        step: PasswordResetStep.otpSent,
        errorMessage: OtpUserMessages.fromError(e, treatAsExpired: expired),
      );
      return false;
    }
  }

  Future<bool> complete({required String newPassword, required String confirmPassword}) async {
    final policyError = AppPasswordPolicy.validateNewPassword(newPassword);
    if (policyError != null) {
      state = state.copyWith(errorMessage: policyError);
      return false;
    }
    if (newPassword != confirmPassword) {
      state = state.copyWith(errorMessage: OtpUserMessages.passwordMismatch);
      return false;
    }
    final token = _resetToken;
    if (token == null) {
      state = state.copyWith(errorMessage: OtpUserMessages.resetTokenInvalid);
      return false;
    }

    state = state.copyWith(step: PasswordResetStep.completing, errorMessage: null);
    try {
      await _ref.read(authRepositoryProvider).completePasswordReset(
            resetToken: token,
            newPassword: newPassword,
          );
      _resetToken = null;
      state = state.copyWith(step: PasswordResetStep.success);
      HapticFeedback.mediumImpact();
      return true;
    } catch (e) {
      state = state.copyWith(
        step: PasswordResetStep.settingPassword,
        errorMessage: OtpUserMessages.fromError(e),
      );
      return false;
    }
  }

  void backToPhone() {
    _countdown?.cancel();
    _sentAt = null;
    _resetToken = null;
    state = const PasswordResetState();
  }

  /// Widget tests cannot fake [DateTime.now]; this forces the expiry path.
  void markExpiredForTest() {
    _sentAt = DateTime.now().subtract(otpLifetime);
  }

  void _startCountdown() {
    _countdown?.cancel();
    _countdown = Timer.periodic(const Duration(seconds: 1), (timer) {
      final remaining = state.resendSecondsRemaining - 1;
      if (remaining <= 0) {
        timer.cancel();
        state = state.copyWith(resendSecondsRemaining: 0, errorMessage: state.errorMessage);
      } else {
        state = state.copyWith(resendSecondsRemaining: remaining, errorMessage: state.errorMessage);
      }
    });
  }

  @override
  void dispose() {
    _countdown?.cancel();
    _resetToken = null;
    super.dispose();
  }
}

final passwordResetControllerProvider =
    StateNotifierProvider.autoDispose<PasswordResetController, PasswordResetState>((ref) {
  return PasswordResetController(ref);
});
