/// India-only phone helpers. Must match backend `IndianPhoneNumbers`:
/// canonical form is `91` + 10-digit mobile (e.g. `919876543210`).
class IndianPhone {
  IndianPhone._();

  static String? normalizeTo91(String raw) {
    var digits = _digitsOnly(raw);
    if (digits == null) return null;
    if (digits.startsWith('00')) {
      digits = digits.substring(2);
    }
    if (RegExp(r'^0[6-9]\d{9}$').hasMatch(digits)) {
      digits = digits.substring(1);
    }
    if (RegExp(r'^[6-9]\d{9}$').hasMatch(digits)) {
      return '91$digits';
    }
    if (RegExp(r'^91[6-9]\d{9}$').hasMatch(digits)) {
      return digits;
    }
    return null;
  }

  static String? toLocal10(String raw) {
    final e164 = normalizeTo91(raw);
    if (e164 == null) return null;
    return e164.substring(2);
  }

  /// Last four digits only, e.g. `******3210`.
  static String mask(String raw) {
    final local = toLocal10(raw);
    if (local == null || local.length < 4) return '******';
    return '******${local.substring(6)}';
  }

  static bool isValid(String raw) => normalizeTo91(raw) != null;

  static String? _digitsOnly(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) return null;
    final buffer = StringBuffer();
    for (final rune in trimmed.runes) {
      final ch = String.fromCharCode(rune);
      if (RegExp(r'\d').hasMatch(ch)) {
        buffer.write(ch);
      } else if (ch == '+' || ch == '-' || ch == ' ' || ch == '(' || ch == ')') {
        continue;
      } else {
        return null;
      }
    }
    return buffer.toString();
  }
}
