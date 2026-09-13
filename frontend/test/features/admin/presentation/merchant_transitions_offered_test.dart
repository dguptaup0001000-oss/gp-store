import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// The buttons the console draws must be the moves the server allows.
///
/// WHY THIS EXISTS. The merchant card used to offer every status on every
/// card, so the platform owner could tap "Approve" on a business still in
/// APPLICATION and be answered "A merchant cannot go from APPLICATION to
/// APPROVED" - a refusal that is correct, arrives after the tap, and explains
/// nothing about what to do instead. The real answer was "Send for review
/// first", and nothing on the screen said so.
///
/// The card now draws only the legal moves. That means the Dart map is a COPY
/// of MerchantStatus.allowedNext, and a copy drifts: somebody adds a state to
/// the enum, the console keeps offering yesterday's moves, and the platform
/// owner is back to tapping buttons that cannot work.
///
/// So this reads BOTH files and compares them. It is deliberately a string
/// comparison against the Java source rather than a fixture, because a fixture
/// would be a third copy to keep in step.
void main() {
  test('the console offers exactly the transitions MerchantStatus allows', () {
    final dart = File(
            'lib/features/admin/presentation/platform_console_screen.dart')
        .readAsStringSync();
    final java = File(
            '../backend/src/main/java/com/gpstore/platform/MerchantStatus.java')
        .readAsStringSync();

    final dartMap = _dartTransitions(dart);
    final javaMap = _javaTransitions(java);

    expect(javaMap, isNotEmpty, reason: 'could not parse allowedNext() at all');
    expect(dartMap, isNotEmpty, reason: 'could not parse _nextFrom at all');

    expect(
      dartMap,
      equals(javaMap),
      reason: 'The Super Admin console and MerchantStatus.allowedNext disagree '
          'about which moves are legal. The server is the rule; the console is '
          'a copy kept so buttons that cannot work are not drawn. Update '
          '_nextFrom in platform_console_screen.dart to match.\n'
          'console: $dartMap\n'
          'server:  $javaMap',
    );
  });
}

/// Reads `'ACTIVE': ['PAUSED', 'SUSPENDED', 'REMOVED'],` out of the Dart map.
Map<String, Set<String>> _dartTransitions(String source) {
  final start = source.indexOf('_nextFrom = {');
  if (start < 0) return {};
  final end = source.indexOf('};', start);
  final body = source.substring(start, end);
  final result = <String, Set<String>>{};
  for (final match in RegExp(r"'([A-Z_]+)':\s*(?:<String>)?\[([^\]]*)\]")
      .allMatches(body)) {
    final from = match.group(1)!;
    final targets = RegExp(r"'([A-Z_]+)'")
        .allMatches(match.group(2)!)
        .map((m) => m.group(1)!)
        .toSet();
    result[from] = targets;
  }
  return result;
}

/// Reads `case ACTIVE -> java.util.EnumSet.of(PAUSED, SUSPENDED, REMOVED);`
/// out of allowedNext(), including the combined `case REJECTED, REMOVED ->`
/// arm that maps several states to the same (empty) answer.
Map<String, Set<String>> _javaTransitions(String source) {
  final start = source.indexOf('allowedNext()');
  if (start < 0) return {};
  final end = source.indexOf('}', source.indexOf('switch (this)', start));
  final body = source.substring(start, end);
  final result = <String, Set<String>>{};

  for (final match
      in RegExp(r'case\s+([A-Z_,\s]+?)\s*->\s*([^;]+);').allMatches(body)) {
    final froms = match
        .group(1)!
        .split(',')
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty);
    final arm = match.group(2)!;
    final targets = arm.contains('noneOf')
        ? <String>{}
        : RegExp(r'\b([A-Z_]{3,})\b')
            .allMatches(arm.substring(arm.indexOf('of(') + 3))
            .map((m) => m.group(1)!)
            .where((s) => s != 'EnumSet')
            .toSet();
    for (final from in froms) {
      result[from] = targets;
    }
  }
  return result;
}
