import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// The rider's APK carries only what pubspec.worker.yaml declares.
///
/// WHY THIS IS NOT THE SAME CHECK AS `flutter analyze`. Every app in this
/// repository is one Dart project with one pubspec, and analyze, `flutter
/// test` and the customer build all resolve against the FULL pubspec.yaml. The
/// delivery-worker APK does not: CI builds it through
/// `tool/with_worker_pubspec.sh`, which swaps in `pubspec.worker.yaml` - a
/// deliberately short list that leaves out Firebase, WebView, TTS, BLE,
/// speech, Cashfree and cached_network_image, so none of that native code is
/// packaged into an APK a rider installs on their own phone.
///
/// So an import that every other check in this repository is happy with can
/// still be a package the worker build cannot resolve. Until this test there
/// was exactly one signal for that: `flutter build apk --flavor worker` dying
/// three minutes into Gradle, in CI, after the merge - which costs a red main
/// branch to read.
///
/// THIS HAPPENED, AND IS WHY THE FILE EXISTS. A tidy-up moved
/// `worker_order_screen.dart` off its raw `Image.network` onto the shared
/// `GpNetworkImage`, which is built on cached_network_image. 842 tests passed,
/// analyze was clean, the customer APK built - and the worker build failed
/// with "Couldn't resolve the package 'cached_network_image'". This walks the
/// same graph the compiler walks, in well under a second, before the push.
void main() {
  test('nothing the worker app imports needs a package it will not have', () {
    final declared = _packagesIn('pubspec.worker.yaml');
    final reached = _graphFrom('lib/worker_main.dart');
    final offenders = <String>[];

    for (final file in reached.keys) {
      for (final package in _packageImports(File(file))) {
        // The project importing itself is not a third-party package.
        if (package == _selfName || declared.contains(package)) continue;
        offenders.add('package:$package\n'
            '    reached from worker_main.dart via:\n'
            '      ${_chain(reached, file).join('\n      ')}');
      }
    }

    expect(
      offenders,
      isEmpty,
      reason: 'These packages are reachable from lib/worker_main.dart but are '
          'not declared in pubspec.worker.yaml, so `flutter build apk '
          '--flavor worker` cannot resolve them and the release build fails.\n'
          'Either keep the worker screens off the shared widget - usually the '
          'right answer, since the short list is the whole reason a rider\'s '
          'APK is small - or add the package to pubspec.worker.yaml '
          'deliberately, knowing its native code then ships to every rider.\n\n'
          '${offenders.join('\n\n')}',
    );
  });

  test('the walk actually reaches the worker screens', () {
    // A GUARD ON THE GUARD. If the import pattern or the path resolution
    // broke, the graph would come back nearly empty and the check above would
    // pass while seeing nothing - the failure mode that makes a test
    // worthless.
    final reached = _graphFrom('lib/worker_main.dart');

    expect(reached.keys, contains('lib/worker_main.dart'));
    expect(
      reached.keys,
      contains('lib/features/worker/presentation/worker_order_screen.dart'),
      reason: 'The packing-list screen must be in the worker graph; if it is '
          'not, this walk is not following imports.',
    );
    // And the customer image pipeline must stay OUTSIDE it, which is the exact
    // thing this file exists to keep true.
    expect(
      reached.keys,
      isNot(contains('lib/core/images/gp_network_image.dart')),
      reason: 'gp_network_image.dart is built on cached_network_image, which '
          'pubspec.worker.yaml does not carry.',
    );
  });
}

/// This project's own package name, so its self-imports are not mistaken for
/// third-party ones.
final String _selfName = RegExp(r'^name:\s*(\S+)', multiLine: true)
    .firstMatch(File('pubspec.yaml').readAsStringSync())!
    .group(1)!;

/// Direct dependency names declared in a pubspec.
///
/// Read with a line pattern rather than a YAML parser because `yaml` is not a
/// direct dependency of this project, and adding one to read a pubspec would
/// be a strange way to pay for a test about not adding dependencies.
Set<String> _packagesIn(String pubspec) {
  final names = <String>{};
  var inDeps = false;

  for (final line in File(pubspec).readAsLinesSync()) {
    if (RegExp(r'^\w').hasMatch(line)) {
      inDeps = line.startsWith('dependencies:') ||
          line.startsWith('dev_dependencies:');
      continue;
    }
    if (!inDeps) continue;
    // Two-space indent is a dependency name; anything deeper is its config.
    final match = RegExp(r'^  (\w[\w_]*):').firstMatch(line);
    if (match != null) names.add(match.group(1)!);
  }

  return names;
}

final _importPattern =
    RegExp('''^\\s*(?:import|export)\\s+['"]([^'"]+)['"]''', multiLine: true);

/// Every `package:` import in a file, as bare package names.
Set<String> _packageImports(File file) {
  final names = <String>{};
  for (final match in _importPattern.allMatches(file.readAsStringSync())) {
    final uri = match.group(1)!;
    if (!uri.startsWith('package:')) continue;
    names.add(uri.substring('package:'.length).split('/').first);
  }
  return names;
}

/// Files reachable from [entry], each mapped to the file that pulled it in, so
/// a failure can print the whole chain rather than just the guilty leaf.
Map<String, String?> _graphFrom(String entry) {
  final reached = <String, String?>{entry: null};
  final pending = <String>[entry];

  while (pending.isNotEmpty) {
    final current = pending.removeLast();
    final file = File(current);
    if (!file.existsSync()) continue;

    for (final match in _importPattern.allMatches(file.readAsStringSync())) {
      final uri = match.group(1)!;
      final String next;
      if (uri.startsWith('package:$_selfName/')) {
        next = _normalise('lib/${uri.substring("package:$_selfName/".length)}');
      } else if (uri.startsWith('package:') || uri.startsWith('dart:')) {
        // Third-party and SDK: checked by name above, not walked into.
        continue;
      } else {
        final dir = current.substring(0, current.lastIndexOf('/'));
        next = _normalise('$dir/$uri');
      }
      if (!next.endsWith('.dart') || reached.containsKey(next)) continue;
      reached[next] = current;
      pending.add(next);
    }
  }
  return reached;
}

/// Collapses the `../` in a relative import into a plain repository path, so
/// one file is one key however it was reached.
String _normalise(String path) {
  final parts = <String>[];
  for (final part in path.split('/')) {
    if (part == '.' || part.isEmpty) continue;
    if (part == '..' && parts.isNotEmpty && parts.last != '..') {
      parts.removeLast();
      continue;
    }
    parts.add(part);
  }
  return parts.join('/');
}

/// worker_main.dart → … → the file carrying the offending import.
List<String> _chain(Map<String, String?> reached, String from) {
  final chain = <String>[];
  String? at = from;
  while (at != null) {
    chain.add(at);
    at = reached[at];
  }
  return chain.reversed.toList();
}
