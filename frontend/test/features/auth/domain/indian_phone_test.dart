import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/auth/domain/indian_phone.dart';

void main() {
  group('IndianPhone', () {
    test('normalises 10-digit, +91, and 91 prefixes without doubling', () {
      expect(IndianPhone.normalizeTo91('9876543210'), '919876543210');
      expect(IndianPhone.normalizeTo91('+919876543210'), '919876543210');
      expect(IndianPhone.normalizeTo91('919876543210'), '919876543210');
      expect(IndianPhone.toLocal10('+91 98765 43210'), '9876543210');
    });

    test('rejects malformed and double-prefixed numbers', () {
      expect(IndianPhone.normalizeTo91('12345'), isNull);
      expect(IndianPhone.normalizeTo91('5123456789'), isNull);
      expect(IndianPhone.normalizeTo91('91919876543210'), isNull);
    });

    test('masks to last four digits', () {
      expect(IndianPhone.mask('9876543210'), '******3210');
      expect(IndianPhone.mask('not-a-phone'), '******');
    });
  });
}
