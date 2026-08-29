import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../domain/otp_user_messages.dart';
import '../domain/shop_email.dart';
import 'auth_providers.dart';

enum OtpFlowStep { enteringPhone, sendingOtp, otpSent, verifying }

class OtpFlowState {
  const OtpFlowState({
    this.step = OtpFlowStep.enteringPhone,
    this.mobileNumber,
    this.errorMessage,
    this.resendSecondsRemaining = 0,
  });

  final OtpFlowStep step;
  final String? mobileNumber;
  final String? errorMessage;
  final int resendSecondsRemaining;

  bool get canResend =>
      step == OtpFlowStep.otpSent && resendSecondsRemaining <= 0;

  OtpFlowState copyWith({
    OtpFlowStep? step,
    String? mobileNumber,
    String? errorMessage,
    int? resendSecondsRemaining,
  }) {
    return OtpFlowState(
      step: step ?? this.step,
      mobileNumber: mobileNumber ?? this.mobileNumber,
      errorMessage: errorMessage,
      resendSecondsRemaining: resendSecondsRemaining ?? this.resendSecondsRemaining,
    );
  }
}

/// Screen-local: only governs the phone-entry -> OTP-sent -> verifying flow
/// itself. Once verification succeeds, it hands off to AuthController (the
/// single source of truth for "is anyone logged in") via setAuthenticated -
/// this controller doesn't duplicate that state.
class OtpFlowController extends StateNotifier<OtpFlowState> {
  OtpFlowController(this._ref) : super(const OtpFlowState());

  final Ref _ref;
  DateTime? _sentAt;
  Timer? _countdown;

  static const resendCooldown = Duration(seconds: 45);
  static const otpLifetime = Duration(minutes: 5);

  Future<bool> sendOtp(String rawEmail) async {
    final email = ShopEmail.normalize(rawEmail);
    if (email == null) {
      state = state.copyWith(errorMessage: OtpUserMessages.invalidEmail);
      return false;
    }

    state = state.copyWith(
      step: OtpFlowStep.sendingOtp,
      mobileNumber: email,
      errorMessage: null,
    );

    try {
      await _ref.read(authRepositoryProvider).requestLoginOtp(email: email);
      _sentAt = DateTime.now();
      state = state.copyWith(
        step: OtpFlowStep.otpSent,
        resendSecondsRemaining: resendCooldown.inSeconds,
      );
      _startCountdown();
      HapticFeedback.lightImpact();
      return true;
    } catch (e) {
      state = state.copyWith(
        step: OtpFlowStep.enteringPhone,
        errorMessage: OtpUserMessages.fromError(e),
      );
      return false;
    }
  }

  Future<bool> resendOtp() async {
    final phone = state.mobileNumber;
    if (phone == null || !state.canResend) return false;
    return sendOtp(phone);
  }

  Future<bool> verifyOtp(String otp) async {
    if (state.mobileNumber == null) return false;

    state = state.copyWith(step: OtpFlowStep.verifying, errorMessage: null);

    try {
      final auth = await _ref.read(authRepositoryProvider).verifyLoginOtp(
            email: state.mobileNumber!,
            otp: otp,
          );

      _countdown?.cancel();
      _ref.read(authControllerProvider.notifier).setAuthenticated(auth);
      HapticFeedback.mediumImpact();
      return true;
    } catch (e) {
      final expired = _sentAt != null && DateTime.now().difference(_sentAt!) >= otpLifetime;
      state = state.copyWith(
        step: OtpFlowStep.otpSent,
        errorMessage: OtpUserMessages.fromError(e, treatAsExpired: expired),
      );
      return false;
    }
  }

  /// Lets the user go back and try a different number without carrying over
  /// stale error state.
  void resetToPhoneEntry() {
    _countdown?.cancel();
    _sentAt = null;
    state = const OtpFlowState();
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
    super.dispose();
  }
}

final otpFlowControllerProvider =
    StateNotifierProvider.autoDispose<OtpFlowController, OtpFlowState>((ref) {
  return OtpFlowController(ref);
});
