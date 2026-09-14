import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Every remote image in every app goes through GpNetworkImage.
///
/// WHAT A RAW Image.network COSTS, which is not style. GpNetworkImage was
/// written to hold three rules that no call site remembers on its own:
///
///   - it CACHES ON DISK, so a list scrolled twice fetches once. Image.network
///     caches only in memory, for as long as the widget lives, so scrolling
///     back up a catalogue re-downloads every photograph;
///   - it ASKS THE CDN FOR THE SIZE IT WILL DRAW, so a 48px row does not pull
///     a 1600px original over a shop's mobile data;
///   - it DECODES AT THE SCREEN'S DENSITY, capped, so twenty tiles do not each
///     hold a full-resolution bitmap - which is exactly what makes a scroll
///     stutter on a cheap phone.
///
/// EIGHT SITES HAD DRIFTED BACK OUT by the time this test was written - one on
/// the rider's packing list and seven across the shopkeeper's screens - and
/// every one of them was a scrolling list of thumbnails, the single worst
/// place to lose all three. Seven were outside because GpNetworkImage drew its
/// stand-in in the storefront palette and those screens are drawn in the admin
/// one. Two palettes is not a reason for two image pipelines, so the palette
/// became an argument and those seven came back in.
///
/// THE EIGHTH IS A REAL EXCEPTION, not drift. The delivery-worker APK is built
/// against `pubspec.worker.yaml`, which deliberately has no
/// cached_network_image - so importing GpNetworkImage from a worker screen
/// does not cost a dependency, it fails the release build outright. See
/// `worker_apk_stays_slim_test.dart`, which walks that import graph and is the
/// test that catches it before a push rather than after a merge.
///
/// The exemption below EXPIRES BY ITSELF: it is allowed only while the worker
/// pubspec really lacks the package. Add cached_network_image there one day
/// and this test starts demanding the conversion, rather than quietly
/// protecting a stale allowlist entry.
void main() {
  test('nothing builds a remote image except GpNetworkImage', () {
    final offenders = <String>[];

    void walk(Directory dir) {
      for (final entity in dir.listSync()) {
        if (entity is Directory) {
          walk(entity);
          continue;
        }
        if (entity is! File || !entity.path.endsWith('.dart')) continue;
        if (_exempt.any(entity.path.endsWith)) continue;

        final lines = entity.readAsLinesSync();
        for (var i = 0; i < lines.length; i++) {
          final line = lines[i];
          // Comments explain the rule in several of these files, including
          // this one's own history. Naming the thing is not doing it.
          if (line.trimLeft().startsWith('//')) continue;
          if (RegExp(r'\bImage\.network\(').hasMatch(line) ||
              RegExp(r'\bNetworkImage\(').hasMatch(line) ||
              // The package underneath. One file is allowed to call it.
              (RegExp(r'\bCachedNetworkImage(Provider)?\(').hasMatch(line) &&
                  !entity.path.endsWith('core/images/gp_network_image.dart'))) {
            offenders.add('${entity.path}:${i + 1}: ${line.trim()}');
          }
        }
      }
    }

    walk(Directory('lib'));

    expect(
      offenders,
      isEmpty,
      reason: 'These bypass GpNetworkImage, so they cache nothing between '
          'scrolls, download the full original to draw a thumbnail, and decode '
          'at the file\'s resolution rather than the screen\'s. Use '
          'GpNetworkImage(url:, renderWidth:) or GpNetworkImage.fill - it takes '
          'placeholderColor / placeholderIconColor / placeholder if this screen '
          'needs its own stand-in:\n${offenders.join('\n')}',
    );
  });

  test('the guard can actually see a bypass', () {
    // A GUARD ON THE GUARD. The check above passes trivially if the patterns
    // stop matching, and a test that cannot fail is worse than no test.
    final direct = RegExp(r'\bImage\.network\(');
    final provider = RegExp(r'\bNetworkImage\(');
    final package = RegExp(r'\bCachedNetworkImage(Provider)?\(');

    expect(direct.hasMatch('      child: Image.network(source,'), isTrue);
    expect(provider.hasMatch('  image: NetworkImage(url),'), isTrue);
    expect(package.hasMatch('    final image = CachedNetworkImage('), isTrue);
    expect(package.hasMatch('  provider: CachedNetworkImageProvider(u),'), isTrue);

    // And does not fire on the widget that holds the rules, or on its name
    // appearing in prose.
    expect(direct.hasMatch('      child: GpNetworkImage(url: source,'), isFalse);
    expect(provider.hasMatch('      child: GpNetworkImage(url: source,'), isFalse);
    expect(package.hasMatch('/// ... through CachedNetworkImage, which is'), isFalse);
  });

  test('the worker exemption is still earned', () {
    // THE EXEMPTION CHECKS ITSELF. It exists only because the delivery-worker
    // APK is built against a pubspec that has no cached_network_image. If that
    // ever stops being true, the reason is gone and the file above must be
    // converted like every other - so this fails then, instead of the
    // allowlist entry silently outliving its justification.
    final worker = File('pubspec.worker.yaml').readAsStringSync();

    expect(
      worker.contains(RegExp(r'^\s*cached_network_image\s*:', multiLine: true)),
      isFalse,
      reason: 'pubspec.worker.yaml now carries cached_network_image, so the '
          'worker screens can use GpNetworkImage like everything else. Convert '
          '${_exempt.join(', ')} and delete this exemption.',
    );
  });
}

/// Files the rule cannot apply to, each with the reason it cannot.
///
/// Kept to file names rather than directories: an exemption wide enough to
/// cover a folder is wide enough to cover the next file somebody puts in it.
const _exempt = <String>[
  // Built under pubspec.worker.yaml, which has no cached_network_image. The
  // test above fails if that stops being true.
  'features/worker/presentation/worker_order_screen.dart',
];
