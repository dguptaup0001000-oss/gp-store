/// Shop OTP identity. Must match backend EmailIdentities.
class ShopEmail {
  ShopEmail._();

  static final _pattern = RegExp(r'^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$');

  static String? normalize(String raw) {
    final email = raw.trim().toLowerCase();
    if (email.isEmpty || email.length > 320 || !_pattern.hasMatch(email)) {
      return null;
    }
    return email;
  }

  static bool isValid(String raw) => normalize(raw) != null;

  /// First character + *** + domain, e.g. `s***@example.com`.
  static String mask(String raw) {
    final email = normalize(raw) ?? raw.trim();
    final at = email.indexOf('@');
    if (at <= 0) return '****';
    return '${email[0]}***${email.substring(at)}';
  }
}
