import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/product_models.dart';

/// Reusable across every horizontal section on the home screen, search
/// results, and category browsing - one widget, one place to fix/improve it.
class ProductCard extends StatelessWidget {
  const ProductCard({
    super.key,
    required this.product,
    this.onTap,
    this.onAddPressed,
    this.isWishlisted = false,
    this.onWishlistToggle,
  });

  final Product product;
  final VoidCallback? onTap;
  final VoidCallback? onAddPressed;

  // Optional and presentational only - if onWishlistToggle is null, no heart
  // icon shows at all. Callers own the actual wishlist state/API calls
  // (see WishlistController) - this widget never talks to Riverpod directly.
  final bool isWishlisted;
  final VoidCallback? onWishlistToggle;

  @override
  Widget build(BuildContext context) {
    final variant = product.primaryVariant;
    final isInStock = variant?.available ?? false;
    final discount = product.discountPercent;

    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
          // Fires the instant the tap registers, not after whatever screen
          // this opens finishes loading - a physical response tied to the
          // finger leaving the glass, same as every reference app (Blinkit
          // etc.) does on every tap, not just ones that end in a network call.
          onTap: onTap == null
              ? null
              : () {
                  HapticFeedback.selectionClick();
                  onTap!();
                },
          child: Padding(
            padding: const EdgeInsets.all(8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Stack(
                  children: [
                    AspectRatio(
                      aspectRatio: 1,
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: variant?.imageUrl != null
                            ? ClipRRect(
                                borderRadius: BorderRadius.circular(8),
                                child: CachedNetworkImage(
                                  imageUrl: variant!.imageUrl!,
                                  fit: BoxFit.contain,
                                  // Grid tiles render at ~150 logical px - without this,
                                  // cached_network_image decodes the full original
                                  // (often several thousand px) into memory for every
                                  // single card on screen, which is real memory/CPU
                                  // pressure across a long scroll. 400 covers up to ~2.5x
                                  // device pixel ratio at this tile size with headroom.
                                  memCacheWidth: 400,
                                  // Explicit error/loading handling per spec
                                  // ("Images: support lazy loading, caching,
                                  // placeholder, error widget") - a broken
                                  // image URL should never show a Flutter
                                  // red-screen crash icon to a customer.
                                  errorWidget: (context, url, error) =>
                                      const Icon(Icons.image_not_supported_outlined, color: AppColors.textSecondary),
                                  placeholder: (context, url) => const Center(
                                    child: SizedBox(
                                      height: 20,
                                      width: 20,
                                      child: CircularProgressIndicator(strokeWidth: 2),
                                    ),
                                  ),
                                ),
                              )
                            : const Icon(Icons.shopping_basket_outlined, color: AppColors.textSecondary),
                      ),
                    ),
                    if (discount != null)
                      Positioned(
                        top: 4,
                        left: 4,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            // Coral, not blue: money-off is the one thing
                            // coral means in this palette, so a shopper can
                            // learn the colour once and scan for it.
                            color: AppColors.accent,
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            '$discount% OFF',
                            style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700),
                          ),
                        ),
                      ),
                    if (onWishlistToggle != null)
                      Positioned(
                        top: 2,
                        right: 2,
                        child: GestureDetector(
                          onTap: onWishlistToggle == null
                              ? null
                              : () {
                                  HapticFeedback.lightImpact();
                                  onWishlistToggle!();
                                },
                          child: Container(
                            padding: const EdgeInsets.all(4),
                            decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                            child: Icon(
                              isWishlisted ? Icons.favorite : Icons.favorite_border,
                              size: 16,
                              color: isWishlisted ? AppColors.error : AppColors.textSecondary,
                            ),
                          ),
                        ),
                      ),
                    if (!isInStock)
                      Positioned.fill(
                        child: Container(
                          decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: 0.7),
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: const Center(
                            child: Text(
                              'Out of stock',
                              style: TextStyle(color: AppColors.textSecondary, fontWeight: FontWeight.w600, fontSize: 11),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  product.name,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodyLarge?.copyWith(fontSize: 13, fontWeight: FontWeight.w500),
                ),
                if (variant?.quantity != null && variant?.unit != null) ...[
                  const SizedBox(height: 2),
                  Text(
                    '${_formatQuantity(variant!.quantity!)} ${variant.unit}',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11),
                  ),
                ],
                const SizedBox(height: 6),
                Row(
                  children: [
                    Expanded(
                      child: variant == null
                          ? const SizedBox.shrink()
                          : Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(
                                  '₹${variant.sellingPrice.toStringAsFixed(0)}',
                                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
                                ),
                                if (variant.mrp != null && variant.mrp! > variant.sellingPrice) ...[
                                  const SizedBox(width: 4),
                                  Text(
                                    '₹${variant.mrp!.toStringAsFixed(0)}',
                                    style: const TextStyle(
                                      fontSize: 11,
                                      color: AppColors.textSecondary,
                                      decoration: TextDecoration.lineThrough,
                                    ),
                                  ),
                                ],
                              ],
                            ),
                    ),
                    // ADD sits on the SAME row as the price rather than on
                    // its own full-width row below it. That removes a whole
                    // ~38px band from every card, which is what actually
                    // makes the card less tall - the image was already
                    // square (AspectRatio(1) above), so the "tall
                    // rectangle" look came from stacked info rows, not from
                    // the image. Keeps the tap target at 32px high and
                    // 64px wide, comfortably above the ~44px-diagonal
                    // minimum for thumbs.
                    SizedBox(
                      height: 32,
                      width: 64,
                      child: OutlinedButton(
                        // Stronger than the card/wishlist taps - this is the
                        // primary "yes, add this" action, so it gets a more
                        // deliberate thud instead of a light click.
                        onPressed: !isInStock || onAddPressed == null
                            ? null
                            : () {
                                HapticFeedback.mediumImpact();
                                onAddPressed!();
                              },
                        style: OutlinedButton.styleFrom(
                          foregroundColor: AppColors.primary,
                          side: const BorderSide(color: AppColors.primary),
                          padding: EdgeInsets.zero,
                        ),
                        child: Text(isInStock ? 'ADD' : 'N/A', style: const TextStyle(fontSize: 12)),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
    );
  }

  // Avoids showing "1.0 kg" - shows "1 kg" for whole numbers, "0.5 kg" otherwise.
  String _formatQuantity(double quantity) {
    return quantity == quantity.roundToDouble()
        ? quantity.toStringAsFixed(0)
        : quantity.toStringAsFixed(1);
  }
}

/// Geometry for product grids, in one place.
///
/// Every grid used to hard-code its own childAspectRatio (0.62 here, 0.78
/// there), so a change to the card silently broke some screens and not
/// others, and nothing recorded WHY a number was what it was.
///
/// The ratio is derived, not guessed. A card is a square image plus a fixed
/// information block:
///
///   height = imageEdge + infoBlock   where imageEdge == cardWidth - padding
///
/// so the taller the info block relative to card width, the smaller the
/// ratio must be. Collapsing price and ADD onto one row removed ~38px from
/// infoBlock, which is what allows a taller (less elongated) ratio here
/// than the 0.62 these grids used before.
///
/// It also responds to the user's text scale: at 1.3x the two-line product
/// name and the price row grow, and a fixed ratio would clip them. Clamped
/// so an extreme accessibility setting cannot produce an absurd card.
class ProductGrid {
  ProductGrid._();

  /// Info block below the square image, at text scale 1.0, in logical px:
  /// name (2 lines) + quantity + price/ADD row + internal spacing + padding.
  static const double _infoBlockAt1x = 104.0;

  /// Card padding, both sides - the image is inset by this within the card.
  static const double _cardPadding = 16.0;

  static double aspectRatio(BuildContext context, {double columns = 2}) {
    final media = MediaQuery.of(context);
    final textScale = media.textScaler.scale(14) / 14;

    // Usable width per card: screen minus outer padding and inter-column gaps.
    final gridWidth = media.size.width - 24 - (12 * (columns - 1));
    final cardWidth = gridWidth / columns;

    final imageEdge = cardWidth - _cardPadding;
    final infoBlock = _infoBlockAt1x * textScale;
    final cardHeight = imageEdge + infoBlock + _cardPadding;

    return (cardWidth / cardHeight).clamp(0.52, 0.85);
  }

  /// Height for a horizontally-scrolling carousel of the same card, so
  /// carousels and grids stay dimensionally consistent instead of drifting
  /// apart. Replaces the hard-coded `height: 220`, which clipped the card at
  /// larger text scales.
  static double carouselHeight(BuildContext context, {double cardWidth = 150}) {
    final textScale = MediaQuery.of(context).textScaler.scale(14) / 14;
    return cardWidth - _cardPadding + (_infoBlockAt1x * textScale) + _cardPadding;
  }
}
