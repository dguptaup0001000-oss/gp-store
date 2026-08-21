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
/// So this asserts the property no unit test can: that no CachedNetworkImage
/// anywhere is handed a raw URL. It is the same idea as the backend's
/// SchemaSafetyGuardTest - a rule about the shape of the codebase, enforced
/// where it can actually be enforced.
void main() {
  test('no CachedNetworkImage is given a raw, untransformed URL', () {
    final offenders = <String>[];

    for (final file in Directory('lib').listSync(recursive: true).whereType<File>()) {
      if (!file.path.endsWith('.dart')) continue;

      final lines = file.readAsLinesSync();
      for (var i = 0; i < lines.length; i++) {
        final line = lines[i].trim();
        if (!line.startsWith('imageUrl:')) continue;

        // Only the ones feeding a network image. A local asset path or a
        // model field assignment is not what this is about, so the scan is
        // anchored to a CachedNetworkImage constructor within a few lines
        // above - which is how the widget is always written here.
        final window = lines.sublist((i - 6).clamp(0, i), i).join(' ');
        if (!window.contains('CachedNetworkImage')) continue;

        final usesSizing = line.contains('ProductImageUrl.');
        if (!usesSizing) {
          offenders.add('${file.path}:${i + 1}  $line');
        }
      }
    }

    expect(
      offenders,
      isEmpty,
      reason: 'These download a full-size original to draw a thumbnail. Wrap the URL in '
          'ProductImageUrl.tile/card/detail/full - memCacheWidth limits the DECODE, '
          'not the DOWNLOAD:\n${offenders.join('\n')}',
    );
  });
}
