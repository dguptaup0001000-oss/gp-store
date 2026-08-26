import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/features/auth/domain/otp_user_messages.dart';

void main() {
  test('invalid phone copy is customer-safe', () {
    expect(
      OtpUserMessages.fromError(ApiException(statusCode: 400, message: 'Enter a valid Indian mobile number')),
      OtpUserMessages.invalidPhone,
    );
  });

  test('rate limits ask the customer to wait', () {
    expect(
      OtpUserMessages.fromError(ApiException(statusCode: 429, message: 'Too many OTP requests. Please try again later.')),
      OtpUserMessages.tooManyAttempts,
    );
  });

  test('wrong OTP is not a stack trace', () {
    expect(
      OtpUserMessages.fromError(ApiException(statusCode: 400, message: 'Invalid or expired OTP')),
      OtpUserMessages.wrongOtp,
    );
  });

  test('elapsed lifetime is shown as expiry', () {
    expect(
      OtpUserMessages.fromError(
        ApiException(statusCode: 400, message: 'Invalid or expired OTP'),
        treatAsExpired: true,
      ),
      OtpUserMessages.otpExpired,
    );
  });

  test('provider failures stay generic', () {
    expect(
      OtpUserMessages.fromError(ApiException(statusCode: 400, message: 'Unable to send OTP right now. Please try again.')),
      OtpUserMessages.sendFailure,
    );
    expect(
      OtpUserMessages.fromError(ApiException(statusCode: 500, message: 'MSG91 authkey missing')),
      OtpUserMessages.sendFailure,
    );
  });

  test('backend 10-character floor is mapped to the same customer copy', () {
    expect(
      OtpUserMessages.fromError(
        ApiException(statusCode: 400, message: 'Password must be 10–128 characters and contain at least one letter and one number.'),
      ),
      OtpUserMessages.passwordTooShort,
    );
    expect(OtpUserMessages.passwordTooShort, contains('10'));
  });
}
