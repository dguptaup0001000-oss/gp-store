import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/design/admin_format.dart';

/// Indian digit grouping is easy to write and easy to get subtly wrong -
/// the first group from the right is three digits and every group after it
/// is two, which is exactly the rule a Western-trained comma routine breaks.
/// A rupee figure with the wrong grouping is not a cosmetic bug: 1,234,567
/// and 12,34,567 read as different amounts to the person the app is for.
void main() {
  group('groupIndian', () {
    test('leaves short numbers alone', () {
      expect(AdminFormat.groupIndian(0), '0');
      expect(AdminFormat.groupIndian(7), '7');
      expect(AdminFormat.groupIndian(999), '999');
    });

    test('groups the first three, then twos', () {
      expect(AdminFormat.groupIndian(1000), '1,000');
      expect(AdminFormat.groupIndian(99999), '99,999');
      // The moment Western grouping would say 100,000.
      expect(AdminFormat.groupIndian(100000), '1,00,000');
      expect(AdminFormat.groupIndian(1234567), '12,34,567');
      expect(AdminFormat.groupIndian(123456789), '12,34,56,789');
    });

    test('keeps the sign outside the grouping', () {
      expect(AdminFormat.groupIndian(-1234567), '-12,34,567');
    });
  });

  group('rupees', () {
    test('rounds to whole rupees', () {
      expect(AdminFormat.rupees(0), '₹0');
      expect(AdminFormat.rupees(1234.49), '₹1,234');
      expect(AdminFormat.rupees(1234.5), '₹1,235');
      expect(AdminFormat.rupees(145000), '₹1,45,000');
    });
  });

  group('rupeesCompact', () {
    // Values chosen to sit clear of a .x5 boundary on purpose. Whether
    // toStringAsFixed rounds 1.45 up or down depends on the exact binary
    // double, not on the decimal you typed, and pinning that here would be
    // testing the platform's rounding rather than this formatter.
    test('uses thousands, lakhs and crores', () {
      expect(AdminFormat.rupeesCompact(999), '₹999');
      expect(AdminFormat.rupeesCompact(1200), '₹1.2K');
      expect(AdminFormat.rupeesCompact(150000), '₹1.5L');
      expect(AdminFormat.rupeesCompact(23000000), '₹2.3Cr');
    });

    test('drops a pointless decimal', () {
      expect(AdminFormat.rupeesCompact(2000), '₹2K');
      expect(AdminFormat.rupeesCompact(100000), '₹1L');
    });

    test('keeps the sign', () {
      expect(AdminFormat.rupeesCompact(-1200), '-₹1.2K');
    });
  });

  group('signedPercent', () {
    test('always shows the sign so it cannot read as a share', () {
      expect(AdminFormat.signedPercent(12.5), '+12.5%');
      expect(AdminFormat.signedPercent(-3), '-3.0%');
      expect(AdminFormat.signedPercent(0), '0.0%');
    });
  });

  group('shortDay', () {
    test('formats the backend bucket key', () {
      expect(AdminFormat.shortDay('2026-08-31'), '31 Aug');
      expect(AdminFormat.shortDay('2026-01-05'), '5 Jan');
    });

    test('returns anything unexpected unchanged rather than throwing', () {
      // A chart axis with an odd label is cosmetic. A chart that throws
      // while painting takes the whole dashboard down.
      expect(AdminFormat.shortDay('not-a-date'), 'not-a-date');
      expect(AdminFormat.shortDay('2026-13-01'), '2026-13-01');
      expect(AdminFormat.shortDay(''), '');
    });
  });
}
