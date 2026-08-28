import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/images/image_url_service.dart';
import 'package:gpstore/core/images/product_image_url.dart';

void main() {
  test('Cloudinary URLs still get delivery transforms', () {
    const url =
        'https://res.cloudinary.com/demo/image/upload/v1/gp-store/products/atta.jpg';
    expect(ImageUrlService.thumbnail(url), ProductImageUrl.tile(url));
    expect(ImageUrlService.medium(url), contains('w_400'));
    expect(ImageUrlService.large(url), contains('w_900'));
    expect(ImageUrlService.original(url), contains('w_1600'));
  });

  test('R2 URLs are returned unchanged at every size', () {
    const url =
        'https://pub-example.r2.dev/gpstore/products/12/original/abc.webp';
    expect(ImageUrlService.thumbnail(url), url);
    expect(ImageUrlService.small(url), url);
    expect(ImageUrlService.medium(url), url);
    expect(ImageUrlService.large(url), url);
    expect(ImageUrlService.original(url), url);
  });
}
