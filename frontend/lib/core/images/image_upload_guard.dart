/// Client-side checks for admin catalogue-image uploads.
///
/// The backend still signs and confirms every object. These checks stop a
/// hostile or accidental file from leaving the phone. Storage credentials
/// never live here.
class ImageUploadGuard {
  static const int maxBytes = 4 * 1024 * 1024;

  /// JPEG / PNG / WebP magic bytes. HTML, SVG, PDF, and executables fail.
  static bool isAllowedImageBytes(List<int> bytes) {
    if (bytes.length < 12) return false;
    if (bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) {
      return true;
    }
    if (bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4E &&
        bytes[3] == 0x47) {
      return true;
    }
    final riff = String.fromCharCodes(bytes.sublist(0, 4));
    final webp = String.fromCharCodes(bytes.sublist(8, 12));
    return riff == 'RIFF' && webp == 'WEBP';
  }

  static String contentTypeForBytes(List<int> bytes) {
    if (bytes.length >= 3 &&
        bytes[0] == 0xFF &&
        bytes[1] == 0xD8 &&
        bytes[2] == 0xFF) {
      return 'image/jpeg';
    }
    if (bytes.length >= 4 &&
        bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4E &&
        bytes[3] == 0x47) {
      return 'image/png';
    }
    if (bytes.length >= 12) {
      final riff = String.fromCharCodes(bytes.sublist(0, 4));
      final webp = String.fromCharCodes(bytes.sublist(8, 12));
      if (riff == 'RIFF' && webp == 'WEBP') return 'image/webp';
    }
    return 'image/jpeg';
  }

  /// Delivery URLs the storefront will load. Dual-read during migration:
  /// Cloudinary (existing rows) and R2 / custom CDN (new uploads).
  static bool isAllowedDeliveryUrl(String url) {
    if (url.startsWith('r2:gpstore/products/') ||
        url.startsWith('r2:gpstore/categories/')) {
      return !url.contains('..');
    }
    final uri = Uri.tryParse(url);
    if (uri == null) return false;
    if (uri.scheme.toLowerCase() != 'https') return false;
    if (uri.userInfo.isNotEmpty) return false;
    final host = uri.host.toLowerCase();
    if (host.isEmpty || host.contains(':')) return false;
    if (host.contains('cloudinary') && host != 'res.cloudinary.com') {
      return false;
    }
    if (host == 'localhost' ||
        host.endsWith('.localhost') ||
        host.endsWith('.local')) {
      return false;
    }
    if (host.endsWith('.r2.cloudflarestorage.com')) return true;
    if (host.endsWith('.r2.dev')) return true;
    if (host == 'res.cloudinary.com') return true;
    return false;
  }

  /// Confirm promotes a staging PUT to this prefix. Staging keys must not
  /// be persisted on the product.
  static bool isPermanentCatalogObjectKey(String key) {
    return (key.startsWith('gpstore/products/') ||
            key.startsWith('gpstore/categories/')) &&
        !key.contains('..') &&
        !key.contains('staging');
  }
}
