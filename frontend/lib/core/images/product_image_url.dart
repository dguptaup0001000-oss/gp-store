/// Requests an appropriately sized image instead of the full original.
///
/// THE PROBLEM THIS SOLVES, precisely. Product cards already pass
/// `memCacheWidth: 400` to CachedNetworkImage, which looks like it limits the
/// image size - and it does, but only the DECODE. The full original is still
/// downloaded over the network, in full, and then thrown away down to 400px.
/// On a category screen showing twenty cards that is twenty full-resolution
/// photographs fetched to render twenty thumbnails, on a phone that may be on
/// mobile data in a village.
///
/// Product images may still be Cloudinary-hosted (legacy rows). Cloudinary
/// resizes on delivery from parameters in the URL path. New uploads go to
/// R2 and are already resized on the phone, so those URLs are returned
/// unchanged.
///
///   .../image/upload/v123/gp-store/products/atta.jpg
///   .../image/upload/w_400,c_limit,f_auto,q_auto/v123/gp-store/products/atta.jpg
///
/// The transformations, and why each one:
///
///   w_N     - cap the width. The only parameter that changes bytes much.
///   c_limit - shrink only, never enlarge. Without it Cloudinary would
///             upscale a small source image to the requested width, which
///             costs bandwidth to deliver a blurrier picture.
///   f_auto  - let Cloudinary pick the format per device, so modern Android
///             receives WebP or AVIF rather than JPEG. Typically the second
///             largest saving after the resize itself.
///   q_auto  - quality chosen per image rather than a fixed number, so a flat
///             packet shot is compressed harder than a detailed one.
///
/// ANY NON-CLOUDINARY URL IS RETURNED UNCHANGED. Admins can paste a plain
/// URL into the variant form, and a product whose image is hosted anywhere
/// else must keep working exactly as before rather than being handed a
/// mangled path.
class ProductImageUrl {
  ProductImageUrl._();

  /// Marks where Cloudinary transformations are inserted.
  static const _uploadSegment = '/image/upload/';

  /// A 2x2 collage tile or a small avatar - tiny on screen.
  static String tile(String? url) => _sized(url, 200);

  /// A product card in a grid or carousel. Cards render at roughly 150-180
  /// logical px; 400 covers that at a 2x device pixel ratio with headroom.
  static String card(String? url) => _sized(url, 400);

  /// The main image on the product detail page.
  static String detail(String? url) => _sized(url, 900);

  /// Full-screen gallery, where the customer is deliberately looking closely
  /// and pinch-zooming. Still capped: no phone benefits from more than this,
  /// and the original can be several thousand pixels wide.
  static String full(String? url) => _sized(url, 1600);

  static String _sized(String? url, int width) {
    if (url == null || url.isEmpty) return '';

    final marker = url.indexOf(_uploadSegment);
    if (marker < 0) return url; // not Cloudinary - leave it alone

    final insertAt = marker + _uploadSegment.length;

    // Already carries transformations: leave it alone rather than stacking a
    // second set, which would silently fight whatever was asked for.
    final remainder = url.substring(insertAt);
    if (_hasTransformations(remainder)) return url;

    return '${url.substring(0, insertAt)}w_$width,c_limit,f_auto,q_auto/$remainder';
  }

  /// Whether the path after /image/upload/ already begins with a Cloudinary
  /// transformation segment.
  ///
  /// WHY THIS IS NOT FOUR startsWith CHECKS ANY MORE. It used to look for
  /// w_, c_, f_ and q_ - the four this class itself writes - which quietly
  /// meant that a URL transformed by anyone ELSE was not recognised as
  /// transformed. Cloudinary has some thirty parameters, and a URL beginning
  /// b_rgb:, l_text:, e_blur, g_face, ar_1:1 or dpr_2.0 sailed past all four
  /// checks and had a second transformation set prepended in front of the
  /// one already there. Chained transformations do still render, so nothing
  /// looked broken - it just stopped doing what the guard was written to do,
  /// which is the kind of bug that survives precisely because it is invisible.
  ///
  /// The grammar is the test rather than a list of prefixes we happen to use:
  /// a transformation segment is comma-separated key_value pairs, and EVERY
  /// part must be one. A folder or public id is not.
  static bool _hasTransformations(String remainder) {
    final slash = remainder.indexOf('/');
    // No slash means there is no segment after this one - so this is the
    // public id itself, not a transformation.
    if (slash <= 0) return false;

    final parts = remainder.substring(0, slash).split(',');
    return parts.every((part) {
      final underscore = part.indexOf('_');
      if (underscore <= 0 || underscore == part.length - 1) return false;
      return _transformationKeys.contains(part.substring(0, underscore));
    });
  }

  /// Cloudinary's transformation parameter keys.
  ///
  /// Listed explicitly rather than accepting any short prefix, so a real
  /// folder that happens to contain an underscore - gp_store/products/... -
  /// is not mistaken for a transformation and left unsized.
  static const Set<String> _transformationKeys = {
    'a', 'ar', 'b', 'bo', 'c', 'co', 'cs', 'd', 'dl', 'dn', 'dpr', 'du',
    'e', 'eo', 'f', 'fl', 'fn', 'g', 'h', 'if', 'ki', 'l', 'o', 'pg', 'q',
    'r', 'so', 't', 'u', 'vc', 'w', 'x', 'y', 'z',
  };
}
