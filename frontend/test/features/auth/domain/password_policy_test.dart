import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/auth/domain/password_policy.dart';

void main() {
  test('new passwords must be at least 10 characters', () {
    expect(AppPasswordPolicy.validateNewPassword('grocery9'),
        AppPasswordPolicy.tooShortMessage);
    expect(AppPasswordPolicy.validateNewPassword('grocery99x'), isNull);
    expect(AppPasswordPolicy.validateNewPassword(''), 'Password is required');
    expect(AppPasswordPolicy.validateNewPassword('passwordaa'),
        AppPasswordPolicy.message);
    expect(AppPasswordPolicy.validateNewPassword('1234567890'),
        AppPasswordPolicy.message);
    expect(AppPasswordPolicy.validateNewPassword('password123'),
        AppPasswordPolicy.message);
  });

  test('hashed denylist still rejects the same weak passwords as the backend',
      () {
    // Plaintext lives only in this test file (not compiled into the APK).
    expect(AppPasswordPolicy.validateNewPassword('password123'),
        AppPasswordPolicy.message);
    expect(AppPasswordPolicy.validateNewPassword('admin123'),
        AppPasswordPolicy.tooShortMessage);
    expect(AppPasswordPolicy.validateNewPassword('password1'),
        AppPasswordPolicy.tooShortMessage);
    expect(AppPasswordPolicy.validateNewPassword('gpstore12'),
        AppPasswordPolicy.tooShortMessage);
    expect(AppPasswordPolicy.validateNewPassword('grocery99x'), isNull);
  });
}
