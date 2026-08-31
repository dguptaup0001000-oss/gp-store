/// Number formatting for the admin console.
///
/// WRITTEN OUT RATHER THAN PULLED FROM intl, and that is a deliberate call.
/// The app does not depend on intl today, and adding a package to put commas
/// in a number would grow the APK for four functions. If localisation ever
/// arrives this file is the single place that changes.
class AdminFormat {
  const AdminFormat._();

  /// Indian digit grouping: 12,34,567 - not 1,234,567.
  ///
  /// The shop is in Uttar Pradesh and its staff read lakhs. Western grouping
  /// on a rupee figure is not a styling preference, it is the wrong number
  /// shape for the person looking at it.
  static String groupIndian(int value) {
    final negative = value < 0;
    final digits = value.abs().toString();

    String grouped;
    if (digits.length <= 3) {
      grouped = digits;
    } else {
      final last3 = digits.substring(digits.length - 3);
      var rest = digits.substring(0, digits.length - 3);
      final parts = <String>[];
      while (rest.length > 2) {
        parts.insert(0, rest.substring(rest.length - 2));
        rest = rest.substring(0, rest.length - 2);
      }
      if (rest.isNotEmpty) parts.insert(0, rest);
      grouped = '${parts.join(',')},$last3';
    }
    return negative ? '-$grouped' : grouped;
  }

  /// Rupees, no paise.
  ///
  /// Paise are dropped on purpose everywhere a KPI or a chart axis is shown:
  /// nobody running a grocery counter needs two decimal places on a month's
  /// takings, and the extra characters are what force a card to ellipsis on
  /// a narrow phone. Screens that must be exact (an invoice, a refund) format
  /// their own figure.
  static String rupees(double amount) =>
      '₹${groupIndian(amount.round())}';

  /// Short form for tight spaces - a chart axis, a dense tile.
  /// 1250 -> ₹1.2K, 145000 -> ₹1.5L, 12500000 -> ₹1.3Cr.
  static String rupeesCompact(double amount) {
    final value = amount.abs();
    final sign = amount < 0 ? '-' : '';
    if (value >= 10000000) {
      return '$sign₹${_trim(value / 10000000)}Cr';
    }
    if (value >= 100000) {
      return '$sign₹${_trim(value / 100000)}L';
    }
    if (value >= 1000) {
      return '$sign₹${_trim(value / 1000)}K';
    }
    return '$sign₹${value.round()}';
  }

  static String count(int value) => groupIndian(value);

  /// "+12.5%" / "-3.0%". The sign is always explicit - a bare "12.5%" beside
  /// a number reads as a share of it, not as a change in it.
  static String signedPercent(double percent) {
    final sign = percent > 0 ? '+' : '';
    return '$sign${percent.toStringAsFixed(1)}%';
  }

  /// "31 Aug" from the backend's "2026-08-31" bucket key.
  ///
  /// Returns the input unchanged if it is not the shape we expect. A chart
  /// axis with an odd label is a cosmetic problem; a chart that throws while
  /// painting takes the dashboard with it.
  static String shortDay(String isoDay) {
    final parts = isoDay.split('-');
    if (parts.length != 3) return isoDay;
    final month = int.tryParse(parts[1]);
    final day = int.tryParse(parts[2]);
    if (month == null || day == null || month < 1 || month > 12) return isoDay;
    return '$day ${_months[month - 1]}';
  }

  static const _months = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
  ];

  /// One decimal, but not a pointless one: 1.0K becomes 1K.
  static String _trim(double value) {
    final text = value.toStringAsFixed(1);
    return text.endsWith('.0') ? text.substring(0, text.length - 2) : text;
  }
}
