import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Every remote image in this app must be requested at the size it is drawn.
///
/// WHY A SOURCE SCAN RATHER THAN A UNIT TEST. product_image_url_test.dart
/// already proves the URL transformation is correct. What it cannot prove is
/// that anyone USES it, and that is the half that actually broke: the fix
/// went into product cards and the Bestsellers collage, while the cart, the
/// wishlist, order detail, the category rows and the cancellation screen all
/// went on downloading full-resolution originals to render 100-200px
/// thumbnails. Eight call sites, silently, for months.
///
/// memCacheWidth is exactly why it stayed invisible. It looks like it caps
/// the image, and it does - it caps the DECODE. The bytes still cross the
/// network. A reviewer sees `memCacheWidth: 160` next to the URL and
/// reasonably concludes the thumbnail is small.
///
/// WHAT CHANGED, AND WHY THIS GUARD GOT STRICTER RATHER THAN LOOSER. Those
/// eleven call sites are now one: GpNetworkImage. The original rule -
/// "every CachedNetworkImage must wrap its URL" - was a rule about eleven
/// places each remembering something. The rule below is a rule about there
/// being only one place, which is not a weaker version of the same check but
/// a stronger one: a call site that does not exist cannot forget.
///
/// Same idea as the backend's SchemaSafetyGuardTest - a rule about the shape
/// of the codebase, enforced where it can actually be enforced.
void main() {
  /// The one file allowed to construct a network image.
  const imageWidget = 'lib/core/images/gp_network_image.dart';

  List<File> dartFiles() => Directory('lib')
      .listSync(recursive: true)
      .whereType<File>()
      .where((f) => f.path.endsWith('.dart'))
      .toList();

  test('CachedNetworkImage is constructed in exactly one place', () {
    final offenders = <String>[];

    for (final file in dartFiles()) {
      // Normalised, so this reads the same on a Windows checkout.
      if (file.path.replaceAll(r'\', '/') == imageWidget) continue;

      final lines = file.readAsLinesSync();
      for (var i = 0; i < lines.length; i++) {
        if (lines[i].contains('CachedNetworkImage(')) {
          offenders.add('${file.path}:${i + 1}  ${lines[i].trim()}');
        }
      }
    }

    expect(
      offenders,
      isEmpty,
      reason: 'Use GpNetworkImage instead. Constructing CachedNetworkImage directly means '
          'restating the CDN size, the decode width, the placeholder and the error widget '
          'by hand - which is how ten surfaces ended up with no placeholder at all:\n'
          '${offenders.join('\n')}',
    );
  });

  test('the one image widget still asks the CDN for a sized URL', () {
    // The guard above only proves the constructions were centralised. This is
    // the half that says centralising them was worth anything: if the widget
    // ever passes a raw URL through, every screen in the app quietly goes back
    // to downloading full-resolution originals - and no screen-level test
    // would notice, because the pictures would still be correct.
    final source = File(imageWidget).readAsStringSync();

    expect(source.contains('imageUrl: _sizedUrl('), isTrue,
        reason: 'The URL handed to CachedNetworkImage must go through _sizedUrl');
    expect(source.contains('ImageUrlService.'), isTrue,
        reason: '_sizedUrl must resolve through ImageUrlService - memCacheWidth limits the '
            'DECODE, not the DOWNLOAD');
  });

  test('the image widget bounds the decode as well as the download', () {
    final source = File(imageWidget).readAsStringSync();

    // Both limits, or neither is enough: an unbounded decode turns a long
    // product grid into real memory pressure even when every file arriving
    // over the network is small.
    expect(source.contains('memCacheWidth:'), isTrue,
        reason: 'Without a decode cap, a 4000px original becomes a 4000px bitmap per tile');
  });
}
