import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/images/image_upload_guard.dart';

void main() {
  test('JPEG PNG and WebP magic bytes are accepted', () {
    expect(
        ImageUploadGuard.isAllowedImageBytes(
            [0xFF, 0xD8, 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        isTrue);
    expect(
        ImageUploadGuard.isAllowedImageBytes(
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
    expect(ImageUploadGuard.isAllowedImageBytes(webp), isTrue);
  });

  test('HTML SVG and empty buffers are rejected', () {
    expect(
        ImageUploadGuard.isAllowedImageBytes('<html>'.codeUnits), isFalse);
    expect(ImageUploadGuard.isAllowedImageBytes('<svg'.codeUnits), isFalse);
    expect(ImageUploadGuard.isAllowedImageBytes([0x00]), isFalse);
  });

  test('HTTPS catalogue hosts are allowed; lookalikes and http are not', () {
    expect(
      ImageUploadGuard.isAllowedDeliveryUrl(
        'https://res.cloudinary.com/demo/image/upload/v1/x.jpg',
      ),
      isTrue,
    );
    expect(
      ImageUploadGuard.isAllowedDeliveryUrl(
        'https://pub-example.r2.dev/gpstore/products/1/original/a.webp',
      ),
      isTrue,
    );
    expect(
      ImageUploadGuard.isAllowedDeliveryUrl(
        'https://acct.r2.cloudflarestorage.com/gp-store-images/gpstore/products/1/original/a.jpg',
      ),
      isTrue,
    );
    expect(
      ImageUploadGuard.isAllowedDeliveryUrl('r2:gpstore/products/1/original/a.jpg'),
      isTrue,
    );
    expect(
      ImageUploadGuard.isAllowedDeliveryUrl(
        'https://images.gpstore.co.in/gpstore/products/1/original/a.jpg',
      ),
      isFalse,
    );
    expect(
      ImageUploadGuard.isAllowedDeliveryUrl(
          'https://res.cloudinary.com.evil/x.jpg'),
      isFalse,
    );
    expect(
        ImageUploadGuard.isAllowedDeliveryUrl(
            'http://res.cloudinary.com/x.jpg'),
        isFalse);
    expect(
        ImageUploadGuard.isAllowedDeliveryUrl(
            'https://user:pass@res.cloudinary.com/x.jpg'),
        isFalse);
  });
}
