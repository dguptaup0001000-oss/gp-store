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
/// Product images are Cloudinary-hosted, and Cloudinary resizes on delivery
/// from parameters in the URL path. So the fix needs no backend change, no
/// new API field, no migration, and no dependency: ask for a narrower image
/// and a narrower image is what arrives.
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
    if (remainder.startsWith('w_') ||
        remainder.startsWith('c_') ||
        remainder.startsWith('f_') ||
        remainder.startsWith('q_')) {
      return url;
    }

    return '${url.substring(0, insertAt)}w_$width,c_limit,f_auto,q_auto/$remainder';
  }
}
