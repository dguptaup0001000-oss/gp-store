import 'product_image_url.dart';

/// One place the storefront asks for a display URL.
///
/// Cloudinary rows still get on-the-fly width transforms. R2 / CDN rows are
/// already resized at upload (max 1600px JPEG) so every size returns the
/// same HTTPS object — do not scatter host-specific URL building in widgets.
class ImageUrlService {
  ImageUrlService._();

  static String thumbnail(String? url) => ProductImageUrl.tile(url);
  static String small(String? url) => ProductImageUrl.tile(url);
  static String medium(String? url) => ProductImageUrl.card(url);
  static String large(String? url) => ProductImageUrl.detail(url);
  static String original(String? url) => ProductImageUrl.full(url);
}
