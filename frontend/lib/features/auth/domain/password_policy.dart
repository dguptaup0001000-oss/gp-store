/// Mirrors backend [PasswordPolicy] for new passwords (register, change, reset).
/// Existing shorter hashes still sign in; the floor applies only when setting
/// a password.
class AppPasswordPolicy {
  AppPasswordPolicy._();

  static const minLength = 10;
  static const maxLength = 128;
  static const tooShortMessage = 'Password must be at least 10 characters.';
  static const message =
      'Password must be 10–128 characters and contain at least one letter and one number.';
  static const helperText =
      'At least 10 characters, with a letter and a number.';

  static const _denylist = {
    'password',
    'password1',
    'password123',
    '12345678',
    '123456789',
    '1234567890',
    'qwertyui',
    'qwerty123',
    'abcdefgh',
    'letmein1',
    'welcome1',
    'admin123',
    'gpstore1',
    'gpstore12',
  };

  static String? validateNewPassword(String? value) {
    if (value == null || value.isEmpty) return 'Password is required';
    if (value.length < minLength) return tooShortMessage;
    if (value.length > maxLength) {
      return 'Password must be at most $maxLength characters.';
    }
    final hasLetter = value.contains(RegExp(r'[A-Za-z]'));
    final hasDigit = value.contains(RegExp(r'[0-9]'));
    if (!hasLetter || !hasDigit) return message;
    if (_denylist.contains(value.toLowerCase())) return message;
    return null;
  }
}
