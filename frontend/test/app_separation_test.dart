import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/shared/app_kind.dart';

void main() {
  test('customer profile has no admin Store Management entry', () {
    final src = File('lib/features/profile/presentation/profile_screen.dart')
        .readAsStringSync();
    expect(src.contains('Store Management'), isFalse);
    expect(src.contains('admin_home_screen'), isFalse);
    expect(src.contains("features/admin/"), isFalse);
  });

  test('customer entrypoint does not import admin features', () {
    bool importsAdmin(String src) =>
        src.contains("features/admin/") ||
        src.contains("admin_home_screen") ||
        src.contains("admin_main.dart");
    expect(importsAdmin(File('lib/customer_main.dart').readAsStringSync()),
        isFalse);
    expect(importsAdmin(File('lib/customer/customer_app.dart').readAsStringSync()),
        isFalse);
    expect(importsAdmin(File('lib/customer/customer_root.dart').readAsStringSync()),
        isFalse);
  });

  test('admin entrypoint does not import the shopping shell', () {
    final src = File('lib/admin/admin_app.dart').readAsStringSync() +
        File('lib/admin/admin_root.dart').readAsStringSync();
    expect(src.contains('customer_shell'), isFalse);
    expect(src.contains('CustomerShell'), isFalse);
    expect(src.contains('features/cart/'), isFalse);
    expect(src.contains('features/checkout/'), isFalse);
  });

  test('AppKind defaults to customer without a dart-define', () {
    expect(AppKind.current, AppKind.customer);
    expect(AppKind.tokenKeyPrefix, isEmpty);
  });
}
