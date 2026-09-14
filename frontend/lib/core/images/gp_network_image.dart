import 'dart:math' as math;

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'image_url_service.dart';

/// Every remote image in the shop, drawn the same way.
///
/// WHY THIS EXISTS. Product and category images were already loading through
/// ProductImageUrl + CachedNetworkImage, which is the right pipeline - but
/// through ELEVEN separate call sites, each restating fit, memCacheWidth and
/// an error widget by hand. The restating was not harmless: exactly one of
/// the eleven bothered with a `placeholder`, so on every other surface a
/// customer on a slow connection watched an empty grey rectangle with no
/// indication anything was coming. Three of them also picked a memCacheWidth
/// by eye, which is a guess about device pixel density baked into a constant.
///
/// This is NOT a second image system. It is the same helper and the same
/// package, called from one place, so the rules below hold everywhere rather
/// than wherever somebody remembered them.
///
/// ONE NUMBER PER CALL SITE. A caller says how wide the image will actually
/// be drawn, in logical pixels, and this works out the rest:
///
///   * which CDN size to ASK FOR - so a 34px cart thumbnail does not pull a
///     900px file over mobile data;
///   * how wide to DECODE - the density of the actual screen, capped, rather
///     than a constant that is wrong on half of Android.
///
/// Those are different limits and both matter: memCacheWidth alone still
/// downloads the full original before throwing the pixels away, and a CDN
/// width alone still decodes at whatever arrives.
class GpNetworkImage extends StatelessWidget {
  const GpNetworkImage({
    super.key,
    required this.url,
    required double this.renderWidth,
    this.fit = BoxFit.contain,
    this.fallbackIcon = Icons.image_not_supported_outlined,
    this.fallbackIconSize,
    this.borderRadius,
    this.placeholderColor,
    this.placeholderIconColor,
    this.placeholder,
  });

  /// For an image that fills whatever box it is handed - a product card in a
  /// grid whose tile width depends on the screen, a collage square, the
  /// detail page hero.
  ///
  /// Measures rather than guesses. The alternative is a constant per call
  /// site, which is a guess about tile width baked into a number, and the
  /// guess is wrong on every screen size except the one it was written on.
  const GpNetworkImage.fill({
    super.key,
    required this.url,
    this.fit = BoxFit.contain,
    this.fallbackIcon = Icons.image_not_supported_outlined,
    this.fallbackIconSize,
    this.borderRadius,
    this.placeholderColor,
    this.placeholderIconColor,
    this.placeholder,
  }) : renderWidth = null;

  /// Straight from the model. Null, empty or unreachable all end up at the
  /// same placeholder - see the class doc: a bad URL must never be the reason
  /// a product card fails to draw.
  final String? url;

  /// How wide this image is actually drawn, in LOGICAL pixels. Not the source
  /// size, not the cache size - what the customer sees.
  ///
  /// Null when built through [GpNetworkImage.fill], which measures instead.
  final double? renderWidth;

  /// contain by default, and that default is deliberate. Grocery photography
  /// is packet shots: an atta bag or a shampoo bottle cropped to fill a square
  /// stops being recognisable, which is the one job the picture has. Category
  /// tiles pass cover, because those are scene photographs where filling the
  /// tile is the point.
  final BoxFit fit;

  final IconData fallbackIcon;
  final double? fallbackIconSize;
  final BorderRadius? borderRadius;

  /// The storefront palette, unless a caller says otherwise.
  ///
  /// WHY THESE EXIST AT ALL. The admin and worker apps had their own raw
  /// Image.network call sites - no disk cache, no CDN size, no decode cap -
  /// and the reason they were never moved onto this widget was cosmetic: a
  /// shopkeeper's catalogue list is drawn in AdminColors, and this widget's
  /// placeholder is drawn in AppColors. Two palettes is not a reason for two
  /// image pipelines, so the palette became an argument.
  ///
  /// Null means the storefront's own colours, which is every customer-app
  /// call site and the reason nothing there had to change.
  final Color? placeholderColor;
  final Color? placeholderIconColor;

  /// A caller's own stand-in, drawn both while the picture is coming and if
  /// it never does.
  ///
  /// For the one case the icon above cannot express: a customer avatar,
  /// where the stand-in is the person's initial rather than a picture icon.
  /// Supplying it must not be the easy path - the shared placeholder is why
  /// a half-loaded grid looks the same everywhere - so it exists for that
  /// case and is null everywhere else.
  final Widget? placeholder;

  /// Decoding beyond 3x buys nothing a human can see and costs real memory on
  /// the densest phones, where a long grid of full-density decodes is exactly
  /// what makes a scroll stutter.
  static const double _maxPixelRatio = 3;

  /// Below the first, a placeholder icon is bigger than the box holding it;
  /// above the second, it stops reading as a quiet stand-in and starts
  /// reading as an error the customer is meant to do something about.
  static const double _minIconSize = 12;
  static const double _maxIconSize = 44;

  /// Used when a fill-sized image is handed an unbounded width - inside a
  /// horizontally scrolling parent, say. Any finite number beats infinity;
  /// this is roughly a phone screen.
  static const double _unboundedFallbackWidth = 400;

  @override
  Widget build(BuildContext context) {
    final width = renderWidth;
    if (width != null) return _build(context, width);

    return LayoutBuilder(
      builder: (context, constraints) => _build(
        context,
        constraints.maxWidth.isFinite ? constraints.maxWidth : _unboundedFallbackWidth,
      ),
    );
  }

  Widget _build(BuildContext context, double width) {
    final source = url;
    if (source == null || source.isEmpty) {
      return _placeholder(width: width, isLoading: false);
    }

    final pixelRatio = math.min(MediaQuery.devicePixelRatioOf(context), _maxPixelRatio);

    final image = CachedNetworkImage(
      imageUrl: _sizedUrl(source, width),
      fit: fit,
      memCacheWidth: (width * pixelRatio).round(),
      // Short. The default half-second cross-fade reads as the app being slow
      // when twenty tiles do it at once during a scroll.
      fadeInDuration: const Duration(milliseconds: 150),
      placeholder: (context, _) => _placeholder(width: width, isLoading: true),
      errorWidget: (context, _, __) => _placeholder(width: width, isLoading: false),
    );

    final radius = borderRadius;
    return radius == null ? image : ClipRRect(borderRadius: radius, child: image);
  }

  String _sizedUrl(String source, double width) {
    // Thresholds sit at the render widths the app actually uses: cart and
    // collage thumbnails, grid and carousel cards, then the detail page hero.
    if (width <= 100) return ImageUrlService.thumbnail(source);
    if (width <= 220) return ImageUrlService.medium(source);
    return ImageUrlService.large(source);
  }

  /// One shape for "still coming" and "never coming".
  ///
  /// No spinner, and that is a choice rather than an omission. A grid of
  /// spinning indicators during a fast scroll is visual noise that draws the
  /// eye to what is missing; a calm tinted block reads as the picture simply
  /// not having arrived yet, which is the truth. It also costs no ticker per
  /// tile, which matters when twenty of them are on screen at once.
  Widget _placeholder({required double width, required bool isLoading}) {
    final own = placeholder;
    if (own != null) return own;

    final size = fallbackIconSize ?? (width * 0.28).clamp(_minIconSize, _maxIconSize);

    return Container(
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: placeholderColor ?? AppColors.surfaceSoft,
        borderRadius: borderRadius,
      ),
      child: Icon(
        isLoading ? Icons.image_outlined : fallbackIcon,
        size: size,
        // Faint on purpose: a placeholder that competes with the real images
        // beside it makes a half-loaded grid look broken rather than busy.
        color: placeholderIconColor ?? AppColors.textSecondary.withValues(alpha: 0.45),
      ),
    );
  }
}
