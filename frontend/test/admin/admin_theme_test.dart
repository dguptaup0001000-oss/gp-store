import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/design/admin_theme.dart';
import 'package:gpstore/admin/design/admin_tokens.dart';
import 'package:gpstore/core/theme/app_theme.dart';

/// The admin theme is the lever that restyles twenty-five screens without
/// editing any of them. Two things must stay true for that to be safe: it
/// must actually be emerald, and it must not have leaked into the shop.
void main() {
  test('the admin theme is emerald on slate, not the shop violet', () {
    final theme = AdminTheme.light;

    expect(theme.colorScheme.primary, AdminColors.primary);
    expect(theme.scaffoldBackgroundColor, AdminColors.background);
    expect(theme.appBarTheme.backgroundColor, AdminColors.surface);

    // The shop's identity, which this must never be.
    expect(theme.colorScheme.primary, isNot(AppTheme.light.colorScheme.primary));
    expect(theme.scaffoldBackgroundColor,
        isNot(AppTheme.light.scaffoldBackgroundColor));
  });

  test('the customer app is untouched - AppTheme still owns the shop', () {
    // Restyling the console must not restyle the shop. If someone ever
    // repaints app_theme.dart emerald to "unify" the two, this fails.
    expect(AppTheme.light.colorScheme.primary, isNot(AdminColors.primary));
  });

  test('success is not the brand green', () {
    // A status colour that equals the button colour means "delivered" and
    // "tap me" render identically. admin_tokens.dart states this rule; this
    // is the test that keeps it true.
    expect(AdminColors.success, isNot(AdminColors.primary));
  });

  test('bars are white while the page is slate, so the header keeps an edge',
      () {
    final theme = AdminTheme.light;
    expect(theme.appBarTheme.backgroundColor,
        isNot(theme.scaffoldBackgroundColor));
  });

  test('admin screens do not reach back into the shop palette', () {
    // The swap to AdminColors was mechanical across twenty files. This is
    // what stops the next screen from importing app_theme out of habit and
    // quietly putting one violet control back into the console.
    final directory = Directory('lib/features/admin/presentation');
    final offenders = <String>[];
    for (final entity in directory.listSync()) {
      if (entity is! File || !entity.path.endsWith('.dart')) continue;
      final source = entity.readAsStringSync();
      if (source.contains('core/theme/app_theme') ||
          source.contains('AppColors.')) {
        offenders.add(entity.uri.pathSegments.last);
      }
    }
    expect(offenders, isEmpty,
        reason: 'these admin screens still use the customer palette');
  });
}
