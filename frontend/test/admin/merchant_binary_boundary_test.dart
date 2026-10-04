import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Walk each APK's Dart import graph. Hidden routes still compile; Merchant
/// Admin must not include platform-control widgets or their API repository.
void main() {
  test('Merchant Admin excludes the platform-control implementation', () {
    final reached = _graphFrom('lib/admin_main.dart');
    expect(reached, isNot(contains(
        'lib/features/admin/presentation/platform_control_tower_screen.dart')));
    expect(reached, isNot(contains(
        'lib/features/admin/data/platform_repository.dart')));
    expect(reached, isNot(contains(
        'lib/features/admin/presentation/platform_providers.dart')));
    for (final path in reached.keys) {
      final file = File(path);
      if (!file.existsSync()) continue;
      final source = file.readAsStringSync();
      expect(source, isNot(contains('PlatformControlTowerScreen')),
          reason: '$path is reachable from Merchant Admin');
      expect(source, isNot(contains('/api/platform/control/')),
          reason: '$path is reachable from Merchant Admin');
    }
  });

  test('Super Admin retains the platform-control implementation', () {
    final reached = _graphFrom('lib/super_admin_main.dart');
    expect(reached, contains(
        'lib/features/admin/presentation/platform_control_tower_screen.dart'));
    expect(reached, contains('lib/features/admin/data/platform_repository.dart'));
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
        next = _normalise(
            '${current.substring(0, current.lastIndexOf('/'))}/$uri');
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
