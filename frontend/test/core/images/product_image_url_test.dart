import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/images/product_image_url.dart';

const _cloudinary =
    'https://res.cloudinary.com/demo/image/upload/v1712345678/gp-store/products/atta.jpg';

void main() {
  group('Cloudinary images are resized on delivery', () {
    test('a card asks for a card-sized image, not the original', () {
      // The bug this fixes: memCacheWidth limits the DECODE, not the
      // DOWNLOAD, so a 3MB original was fetched in full to render a 150px
      // thumbnail - twenty times over on a category screen.
      final url = ProductImageUrl.card(_cloudinary);
      expect(url, contains('w_400'));
      expect(url, contains('/image/upload/w_400'));
    });

    test('each surface asks for a size that suits it', () {
      expect(ProductImageUrl.tile(_cloudinary), contains('w_200'));
      expect(ProductImageUrl.card(_cloudinary), contains('w_400'));
      expect(ProductImageUrl.detail(_cloudinary), contains('w_900'));
      expect(ProductImageUrl.full(_cloudinary), contains('w_1600'));
    });

    test('sizes increase from tile to full screen', () {
      int widthOf(String url) =>
          int.parse(RegExp(r'w_(\d+)').firstMatch(url)!.group(1)!);

      expect(widthOf(ProductImageUrl.tile(_cloudinary)),
          lessThan(widthOf(ProductImageUrl.card(_cloudinary))));
      expect(widthOf(ProductImageUrl.card(_cloudinary)),
          lessThan(widthOf(ProductImageUrl.detail(_cloudinary))));
      expect(widthOf(ProductImageUrl.detail(_cloudinary)),
          lessThan(widthOf(ProductImageUrl.full(_cloudinary))));
    });

    test('shrinks only, never upscales', () {
      // Without c_limit a small source is enlarged to the requested width -
      // more bytes to deliver a blurrier picture.
      expect(ProductImageUrl.card(_cloudinary), contains('c_limit'));
    });

    test('lets Cloudinary choose format and quality per device', () {
      // f_auto serves WebP/AVIF to modern Android instead of JPEG, typically
      // the second largest saving after the resize.
      final url = ProductImageUrl.card(_cloudinary);
      expect(url, contains('f_auto'));
      expect(url, contains('q_auto'));
    });

    test('the rest of the path is preserved exactly', () {
      // The version and folder identify the image; mangling either produces
      // a 404 rather than a smaller picture.
      final url = ProductImageUrl.card(_cloudinary);
      expect(url, contains('/v1712345678/gp-store/products/atta.jpg'));
      expect(url, startsWith('https://res.cloudinary.com/demo/image/upload/'));
    });
  });

  group('anything else is left alone', () {
    test('a non-Cloudinary URL is returned unchanged', () {
      // Admins can paste any URL into the variant form. A product hosted
      // elsewhere must keep working, not be handed a mangled path.
      const plain = 'https://example.com/images/atta.jpg';
      expect(ProductImageUrl.card(plain), plain);
      expect(ProductImageUrl.full(plain), plain);
    });

    test('a URL that already carries transformations is not double-wrapped', () {
      // Stacking a second set would silently fight whatever was asked for.
      const already =
          'https://res.cloudinary.com/demo/image/upload/w_800/v1/gp-store/products/atta.jpg';
      expect(ProductImageUrl.detail(already), already);
    });

    test('null and empty produce an empty string, not a crash', () {
      expect(ProductImageUrl.card(null), isEmpty);
      expect(ProductImageUrl.card(''), isEmpty);
    });

    test('a malformed URL is passed through rather than corrupted', () {
      const odd = 'not-really-a-url';
      expect(ProductImageUrl.card(odd), odd);
    });
  });
}
