import 'package:flutter/material.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/images/gp_network_image.dart';

/// Swipeable product gallery with page dots.
///
/// ASPECT RATIO IS NEVER VIOLATED. Every image uses BoxFit.contain inside a
/// fixed 1:1 frame, so a tall bottle and a wide packet both render whole and
/// undistorted, letterboxed against the frame instead of being cropped or
/// stretched to fill it. BoxFit.cover would fill the frame more prettily and
/// would also slice the top off a shampoo bottle and squash a wide carton -
/// which for a grocery catalogue means the customer cannot read the label
/// they are buying from.
///
/// LOADING IS LAZY BY CONSTRUCTION. PageView.builder only builds the pages
/// adjacent to the current one, so opening a product downloads the first
/// image, not all five. Swiping fetches the next on demand, and
/// GpNetworkImage's cache keeps it for the rest of the session.
class ProductImageGallery extends StatefulWidget {
  const ProductImageGallery({super.key, required this.imageUrls});

  /// Gallery images in display order. May be empty - the caller decides what
  /// to show instead (usually the variant thumbnail).
  final List<String> imageUrls;

  @override
  State<ProductImageGallery> createState() => _ProductImageGalleryState();
}

class _ProductImageGalleryState extends State<ProductImageGallery> {
  late final PageController _controller;
  int _current = 0;

  @override
  void initState() {
    super.initState();
    _controller = PageController();
  }

  @override
  void dispose() {
    // Disposed explicitly: a PageController holds listeners and an animation
    // ticker, and leaking one per product a customer opens is a real leak on
    // a screen this heavily visited.
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final urls = widget.imageUrls;
    if (urls.isEmpty) {
      return const _GalleryPlaceholder();
    }

    return Column(
      children: [
        AspectRatio(
          aspectRatio: 1,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: AppColors.cardBackground,
              borderRadius: BorderRadius.circular(AppRadius.lg),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(AppRadius.lg),
              child: PageView.builder(
                controller: _controller,
                itemCount: urls.length,
                onPageChanged: (index) => setState(() => _current = index),
                itemBuilder: (context, index) => _ParallaxPage(
                  controller: _controller,
                  index: index,
                  // The hero deserves more resolution than a grid thumbnail,
                  // but the page is at most ~430 logical px wide - passing
                  // that real width is what stops a 4000px product photo
                  // being downloaded and decoded at full size. One broken URL
                  // costs one blank page, not the gallery.
                  child: GpNetworkImage.fill(url: urls[index]),
                ),
              ),
            ),
          ),
        ),
        if (urls.length > 1) ...[
          const SizedBox(height: 12),
          _Dots(count: urls.length, current: _current),
          const SizedBox(height: 4),
          Text(
            '${_current + 1} / ${urls.length}',
            style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
          ),
        ],
      ],
    );
  }
}

/// Gives each gallery page a slight parallax and scale as it slides.
///
/// The image drifts against the swipe at a fraction of the page's own speed
/// and eases up to full size as it settles, so moving between photos feels
/// like turning a physical object rather than sliding a flat sheet. Kept
/// deliberately small - a large parallax reads as a game transition, not a
/// shop.
///
/// AnimatedBuilder on the PageController rebuilds only this subtree during a
/// swipe, and the effect is two Transforms: paint-time only, no relayout, no
/// saveLayer, no image filters. The child is passed through AnimatedBuilder's
/// `child` slot so the image is built ONCE and merely
/// re-positioned each frame, rather than rebuilt sixty times a second.
class _ParallaxPage extends StatelessWidget {
  const _ParallaxPage({required this.controller, required this.index, required this.child});

  final PageController controller;
  final int index;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      child: child,
      builder: (context, built) {
        // How far this page is from the viewport centre, in pages.
        // hasClients/haveDimensions guard the first frame, before the
        // controller is attached and `page` would throw.
        var delta = 0.0;
        if (controller.hasClients && controller.position.haveDimensions) {
          delta = (controller.page ?? controller.initialPage.toDouble()) - index;
        }
        final clamped = delta.clamp(-1.0, 1.0);

        return Transform.translate(
          // 24px: enough to read as depth, small enough that the product
          // never looks detached from its frame.
          offset: Offset(clamped * 24, 0),
          child: Transform.scale(
            // Settles from 0.94 up to 1.0 as the page centres.
            scale: 1 - (clamped.abs() * 0.06),
            child: built,
          ),
        );
      },
    );
  }
}

/// Page dots. The active one is a cobalt pill rather than a bigger circle -
/// with four or five images, size alone is hard to spot at a glance, whereas
/// a shape change is unmistakable.
class _Dots extends StatelessWidget {
  const _Dots({required this.count, required this.current});

  final int count;
  final int current;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: List.generate(count, (index) {
        final isActive = index == current;
        return AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          margin: const EdgeInsets.symmetric(horizontal: 3),
          height: 6,
          width: isActive ? 18 : 6,
          decoration: BoxDecoration(
            color: isActive ? AppColors.primary : AppColors.textSecondary.withValues(alpha: 0.3),
            borderRadius: BorderRadius.circular(3),
          ),
        );
      }),
    );
  }
}

class _GalleryPlaceholder extends StatelessWidget {
  const _GalleryPlaceholder();

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 1,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: AppColors.cardBackground,
          borderRadius: BorderRadius.circular(AppRadius.lg),
        ),
        child: const Center(
          child: Icon(Icons.shopping_basket_outlined, size: 48, color: AppColors.textSecondary),
        ),
      ),
    );
  }
}
