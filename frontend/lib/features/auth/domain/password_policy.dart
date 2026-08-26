/// Mirrors backend [PasswordPolicy] for new passwords (register, change, reset).
/// Existing shorter hashes still sign in; the floor applies only when setting
/// a password.
class AppPasswordPolicy {
  AppPasswordPolicy._();

  static const minLength = 10;
  static const maxLength = 128;
  static const tooShortMessage = 'Password must be at least 10 characters.';

  static String? validateNewPassword(String? value) {
    if (value == null || value.isEmpty) return 'Password is required';
    if (value.length < minLength) return tooShortMessage;
    if (value.length > maxLength) {
      return 'Password must be at most $maxLength characters.';
    }
    return null;
  }
}
