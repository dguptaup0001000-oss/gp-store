import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/support/domain/store_info_model.dart';

void main() {
  test('placeholders from JSON are treated as empty', () {
    final info = StoreInfo.fromJson({
      'supportPhone': '+91XXXXXXXXXX',
      'supportWhatsapp': '',
      'supportEmail': 'support@example.com',
      'supportUrl': 'https://example.com/help',
      'onlinePaymentEnabled': false,
      'upiConfigured': false,
    });
    expect(info.hasAnyContact, isFalse);
    expect(info.supportPhone, isEmpty);
    expect(info.supportEmail, isEmpty);
    expect(info.onlinePaymentEnabled, isFalse);
    expect(info.upiConfigured, isFalse);
  });

  test('real contacts and payment flags parse', () {
    final info = StoreInfo.fromJson({
      'supportPhone': '+919876543210',
      'supportWhatsapp': '+919876543210',
      'supportEmail': 'hello@gpstore.co.in',
      'supportUrl': 'https://gpstore.co.in/help',
      'onlinePaymentEnabled': true,
      'upiConfigured': true,
    });
    expect(info.hasAnyContact, isTrue);
    expect(info.onlinePaymentEnabled, isTrue);
    expect(info.upiConfigured, isTrue);
    expect(info.coercePaymentMethod('ONLINE'), 'ONLINE');
    expect(info.coercePaymentMethod('UPI'), 'UPI');
  });

  test('checkout falls back to COD when gateways are off', () {
    expect(StoreInfo.coercePaymentMethodFor('ONLINE', null), 'COD');
    expect(StoreInfo.coercePaymentMethodFor('UPI', null), 'COD');
    expect(StoreInfo.coercePaymentMethodFor('COD', null), 'COD');
    const off = StoreInfo(
      supportPhone: '',
      supportWhatsapp: '',
      supportEmail: '',
    );
    expect(off.coercePaymentMethod('ONLINE'), 'COD');
    expect(off.coercePaymentMethod('UPI'), 'COD');
  });
}
