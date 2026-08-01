import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'auth_providers.dart';

enum OtpFlowStep { enteringPhone, sendingOtp, otpSent, verifying }

class OtpFlowState {
  const OtpFlowState({
    this.step = OtpFlowStep.enteringPhone,
    this.mobileNumber,
    this.errorMessage,
  });

  final OtpFlowStep step;
  final String? mobileNumber;
  final String? errorMessage;

  OtpFlowState copyWith({OtpFlowStep? step, String? mobileNumber, String? errorMessage}) {
    return OtpFlowState(
      step: step ?? this.step,
      mobileNumber: mobileNumber ?? this.mobileNumber,
      errorMessage: errorMessage,
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

  Future<bool> sendOtp(String mobileNumber) async {
    state = state.copyWith(step: OtpFlowStep.sendingOtp, mobileNumber: mobileNumber, errorMessage: null);

    try {
      await _ref.read(authRepositoryProvider).sendOtp(mobileNumber: mobileNumber);
      state = state.copyWith(step: OtpFlowStep.otpSent);
      return true;
    } catch (e) {
      state = state.copyWith(step: OtpFlowStep.enteringPhone, errorMessage: extractErrorMessage(e));
      return false;
    }
  }

  Future<bool> verifyOtp(String otp) async {
    if (state.mobileNumber == null) return false;

    state = state.copyWith(step: OtpFlowStep.verifying, errorMessage: null);

    try {
      final auth = await _ref
          .read(authRepositoryProvider)
          .verifyOtp(mobileNumber: state.mobileNumber!, otp: otp);

      _ref.read(authControllerProvider.notifier).setAuthenticated(auth);
      return true;
    } catch (e) {
      state = state.copyWith(step: OtpFlowStep.otpSent, errorMessage: extractErrorMessage(e));
      return false;
    }
  }

  /// Lets the user go back and try a different number, or resend after a
  /// failed attempt, without carrying over stale error state.
  void resetToPhoneEntry() {
    state = const OtpFlowState();
  }
}

final otpFlowControllerProvider =
    StateNotifierProvider.autoDispose<OtpFlowController, OtpFlowState>((ref) {
  return OtpFlowController(ref);
});
