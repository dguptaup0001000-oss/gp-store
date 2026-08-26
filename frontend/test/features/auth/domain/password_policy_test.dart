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
}
