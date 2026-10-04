import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// The Super Admin app is a separate release entrypoint. Its role check is
/// useful UX, but compiling merchant-only shop switching into that APK is
/// unnecessary surface area and makes the release separation unverifiable.
void main() {
  test('Super Admin import graph excludes merchant shop switching', () {
    final reached = _graphFrom('lib/super_admin_main.dart');

    expect(reached, isNot(contains('lib/admin/shell/shop_switcher_bar.dart')));
    expect(reached, isNot(contains('lib/admin/shell/shop_switch.dart')));
    expect(reached, isNot(contains('lib/admin/shell/admin_destinations.dart')));
    expect(
        reached, isNot(contains('lib/features/admin/presentation/admin_printer_settings_screen.dart')));
    expect(reached, isNot(contains('lib/shared/bootstrap.dart')));
    expect(reached, contains('lib/admin/super_admin_root.dart'));
    expect(reached, contains('lib/admin/shell/admin_shell.dart'));
  });

  test('Super Admin plugin set excludes shop-only native plugins', () {
    final pubspec = File('pubspec.superadmin.yaml').readAsStringSync();
    for (final package in [
      'flutter_cashfree_pg_sdk',
      'print_bluetooth_thermal',
      'esc_pos_utils_plus',
      'firebase_core',
      'firebase_messaging',
      'flutter_tts',
      'mobile_scanner',
      'geolocator',
    ]) {
      expect(pubspec, isNot(contains('$package:')),
          reason: '$package is not used by the platform control app');
    }
    final reached = _graphFrom('lib/super_admin_main.dart');
    final imports = <String>{};
    final importPattern = RegExp(r'''^\s*(?:import|export)\s+['"]([^'"]+)['"]''',
        multiLine: true);
    for (final path in reached.keys) {
      final file = File(path);
      if (!file.existsSync()) continue;
      for (final match in importPattern.allMatches(file.readAsStringSync())) {
        final uri = match.group(1)!;
        if (uri.startsWith('package:') && !uri.startsWith('package:gpstore/')) {
          imports.add(uri.split('/').first.substring('package:'.length));
        }
      }
    }
    final declared = RegExp(r'^  ([\w_]+):', multiLine: true)
        .allMatches(pubspec)
        .map((match) => match.group(1)!)
        .toSet();
    expect(imports.difference({'flutter'})..removeAll(declared), isEmpty,
        reason: 'every direct package import reachable from Super Admin must '
            'be declared in pubspec.superadmin.yaml');
  });

  test('only the merchant entrypoint injects the shop selector', () {
    final shell = File('lib/admin/shell/admin_shell.dart').readAsStringSync();
    final merchant = File('lib/admin/admin_root.dart').readAsStringSync();
    final platform = File('lib/admin/super_admin_root.dart').readAsStringSync();

    expect(shell, isNot(contains("import 'shop_switcher_bar.dart'")));
    expect(merchant, contains("import 'shell/shop_switcher_bar.dart'"));
    expect(merchant, contains('shopSwitcher:'));
    expect(platform, isNot(contains('shopSwitcher:')));
  });
}

Map<String, String?> _graphFrom(String entry) {
  final reached = <String, String?>{entry: null};
  final pending = <String>[entry];
  final imports = RegExp(r'''^\s*(?:import|export)\s+['"]([^'"]+)['"]''',
      multiLine: true);

  while (pending.isNotEmpty) {
    final current = pending.removeLast();
    final file = File(current);
    if (!file.existsSync()) continue;
    for (final match in imports.allMatches(file.readAsStringSync())) {
      final uri = match.group(1)!;
      final String next;
      if (uri.startsWith('package:gpstore/')) {
        next = _normalise('lib/${uri.substring('package:gpstore/'.length)}');
      } else if (uri.startsWith('package:') || uri.startsWith('dart:')) {
        continue;
      } else {
        next = _normalise('${current.substring(0, current.lastIndexOf('/'))}/$uri');
      }
      if (!next.endsWith('.dart') || reached.containsKey(next)) continue;
      reached[next] = current;
      pending.add(next);
    }
  }
  return reached;
}

String _normalise(String path) {
  final parts = <String>[];
  for (final part in path.split('/')) {
    if (part == '.' || part.isEmpty) continue;
    if (part == '..' && parts.isNotEmpty && parts.last != '..') {
      parts.removeLast();
    } else {
      parts.add(part);
    }
  }
  return parts.join('/');
}
