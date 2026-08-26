import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/cloudinary_upload_guard.dart';

void main() {
  test('JPEG PNG and WebP magic bytes are accepted', () {
    expect(
        CloudinaryUploadGuard.isAllowedImageBytes(
            [0xFF, 0xD8, 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        isTrue);
    expect(
        CloudinaryUploadGuard.isAllowedImageBytes(
            [0x89, 0x50, 0x4E, 0x47, 0, 0, 0, 0, 0, 0, 0, 0]),
        isTrue);
    final webp = <int>[
      0x52,
      0x49,
      0x46,
      0x46,
      0,
      0,
      0,
      0,
      0x57,
      0x45,
      0x42,
      0x50,
    ];
    expect(CloudinaryUploadGuard.isAllowedImageBytes(webp), isTrue);
  });

  test('HTML and empty buffers are rejected', () {
    expect(
        CloudinaryUploadGuard.isAllowedImageBytes('<html>'.codeUnits), isFalse);
    expect(CloudinaryUploadGuard.isAllowedImageBytes([0x00]), isFalse);
  });

  test('only HTTPS Cloudinary delivery URLs count', () {
    expect(
      CloudinaryUploadGuard.isAllowedDeliveryUrl(
        'https://res.cloudinary.com/demo/image/upload/v1/x.jpg',
      ),
      isTrue,
    );
    expect(
      CloudinaryUploadGuard.isAllowedDeliveryUrl(
          'https://res.cloudinary.com.evil/x.jpg'),
      isFalse,
    );
    expect(
        CloudinaryUploadGuard.isAllowedDeliveryUrl(
            'http://res.cloudinary.com/x.jpg'),
        isFalse);
  });

  test('filenames cannot carry a path', () {
    expect(CloudinaryUploadGuard.safeFilename('../../etc/passwd'), 'passwd');
    expect(CloudinaryUploadGuard.safeFilename(''), 'image.jpg');
  });
}
