/// Client-side checks for admin product-image uploads.
///
/// Cloudinary still rejects garbage; this stops a failed or hostile file
/// from being treated as a product photo URL. Storage credentials never
/// live in this file — only the public delivery host is named.
class CloudinaryUploadGuard {
  static const int maxBytes = 4 * 1024 * 1024;
  static const String deliveryHost = 'res.cloudinary.com';

  /// JPEG / PNG / WebP magic bytes. HTML, PDF, and executables fail.
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

  static bool isAllowedDeliveryUrl(String url) {
    final uri = Uri.tryParse(url);
    if (uri == null) return false;
    if (uri.scheme.toLowerCase() != 'https') return false;
    if (uri.userInfo.isNotEmpty) return false;
    return uri.host.toLowerCase() == deliveryHost;
  }

  static String safeFilename(String raw) {
    var name = raw.replaceAll('\\', '/').split('/').last;
    name = name.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
    if (name.isEmpty || name.startsWith('.')) name = 'image.jpg';
    if (name.length > 80) name = name.substring(name.length - 80);
    return name;
  }
}
