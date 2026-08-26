import '../../../core/api/api_client.dart';
import '../../../core/api/error_messages.dart';
import 'password_policy.dart';

/// Customer-facing OTP copy. Maps backend messages without exposing MSG91
/// internals, stack traces, or OTP values.
class OtpUserMessages {
  OtpUserMessages._();

  static const invalidPhone = 'Please enter a valid Indian mobile number.';
  static const tooManyAttempts = 'Too many attempts. Please try again later.';
  static const otpExpired = 'This OTP has expired. Please request a new OTP.';
  static const wrongOtp = 'Incorrect OTP. Please try again.';
  static const sendFailure = 'Unable to send OTP right now. Please try again.';
  static const network = 'You appear to be offline. Check your connection and try again.';
  static const passwordTooShort = AppPasswordPolicy.tooShortMessage;
  static const passwordMismatch = 'The two passwords do not match.';
  static const resetTokenInvalid = 'This reset step has expired. Please request a new OTP.';
  static const resetSuccess = 'Password reset - you can now log in with your new password.';

  static String fromError(Object error, {bool treatAsExpired = false}) {
    if (treatAsExpired) return otpExpired;

    final raw = extractErrorMessage(error).trim();
    final status = _statusOf(error);
    final lower = raw.toLowerCase();

    if (status == 429 || lower.contains('too many')) {
      return tooManyAttempts;
    }
    if (lower.contains('valid indian mobile') || lower.contains('valid 10-digit')) {
      return invalidPhone;
    }
    if (lower.contains('unable to send otp')) {
      return sendFailure;
    }
    if (lower.contains('too many incorrect')) {
      return 'Too many incorrect attempts. Please request a new OTP.';
    }
    if (lower.contains('expired reset') || lower.contains('invalid or expired reset')) {
      return resetTokenInvalid;
    }
    if (lower.contains('at least 8') ||
        lower.contains('at least 10') ||
        lower.contains('10–128') ||
        lower.contains('10-128')) {
      return passwordTooShort;
    }
    if (lower.contains('offline') || lower.contains('could not reach')) {
      return network;
    }
    if (lower.contains('slow') || lower.contains('timed out')) {
      return 'The connection is slow right now. Please try again in a moment.';
    }
    if (lower.contains('invalid or expired otp')) {
      return wrongOtp;
    }
    if (raw.isEmpty) return sendFailure;
    // Backend generic sentences are already safe; never pass through HTML
    // or anything that looks like a stack / provider dump.
    if (lower.contains('exception') || lower.contains('msg91') || lower.contains('authkey')) {
      return sendFailure;
    }
    return raw;
  }

  static int? _statusOf(Object error) {
    if (error is ApiException) return error.statusCode;
    return null;
  }
}
