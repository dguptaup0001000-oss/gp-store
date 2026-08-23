import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/product_models.dart';
import '../../core/images/gp_network_image.dart';
import '../../core/util/haptic_widgets.dart';

/// Reusable across every horizontal section on the home screen, search
/// results, and category browsing - one widget, one place to fix/improve it.
class ProductCard extends StatefulWidget {
  const ProductCard({
    super.key,
    required this.product,
    this.onTap,
    this.onAddPressed,
    this.isWishlisted = false,
    this.onWishlistToggle,
    this.quantityInCart = 0,
    this.onIncrement,
    this.onDecrement,
  });

  final Product product;
  final VoidCallback? onTap;
  final VoidCallback? onAddPressed;

  /// How many of this product's variant are already in the cart. Above zero,
  /// the ADD button is replaced by a - / n / + stepper, so a customer buying
  /// three of something never leaves the grid.
  ///
  /// Passed IN rather than read from Riverpod here, deliberately: this widget
  /// stays presentational and testable, and callers keep owning cart state -
  /// the same contract the wishlist props already follow. CartAwareProductCard
  /// does the binding in one place so no screen repeats it.
  final int quantityInCart;
  final VoidCallback? onIncrement;
  final VoidCallback? onDecrement;

  // Optional and presentational only - if onWishlistToggle is null, no heart
  // icon shows at all. Callers own the actual wishlist state/API calls
  // (see WishlistController) - this widget never talks to Riverpod directly.
  final bool isWishlisted;
  final VoidCallback? onWishlistToggle;

  @override
  State<ProductCard> createState() => _ProductCardState();
}

class _ProductCardState extends State<ProductCard> {
  bool _pressed = false;

  void _setPressed(bool value) {
    if (_pressed == value) return;
    setState(() => _pressed = value);
  }

  @override
  Widget build(BuildContext context) {
    // Locals for every prop, so the widget body below reads exactly as it did
    // when this was a StatelessWidget - the split into State is for the press
    // animation only, not a rewrite of the card.
    final product = widget.product;
    final onTap = widget.onTap;
    final onAddPressed = widget.onAddPressed;
    final isWishlisted = widget.isWishlisted;
    final onWishlistToggle = widget.onWishlistToggle;
    final quantityInCart = widget.quantityInCart;
    final onIncrement = widget.onIncrement;
    final onDecrement = widget.onDecrement;

    final variant = product.primaryVariant;
    final isInStock = variant?.available ?? false;
    final discount = product.discountPercent;

    // RepaintBoundary so the press animation repaints THIS card only. Without
    // it, one finger-down marks the whole scrolling grid dirty and every
    // visible card re-rasterises for the length of the animation.
    return RepaintBoundary(
      child: AnimatedScale(
        // 0.97, not 0.9: the card should feel pressed, not launched. Anything
        // larger reads as a game UI rather than a product sitting on a shelf.
        scale: _pressed ? 0.97 : 1.0,
        duration: const Duration(milliseconds: 110),
        curve: Curves.easeOut,
        // Scale is a paint-time transform - no relayout, no saveLayer.
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 110),
          curve: Curves.easeOut,
          decoration: BoxDecoration(
            color: AppColors.cardBackground,
            borderRadius: BorderRadius.circular(AppRadius.md),
            // Two-layer shadow that tightens under the finger - see
            // AppElevation for why a pressed object's shadow shrinks rather
            // than fades.
            boxShadow: _pressed ? AppElevation.cardPressed : AppElevation.card,
          ),
          // Deliberately NOT Clip.antiAlias, which the Material Card this
          // replaces used: clipping forces a saveLayer per card per frame,
          // and nothing here paints outside the rounded rect anyway.
          child: Material(
            type: MaterialType.transparency,
            child: InkWell(
              borderRadius: BorderRadius.circular(AppRadius.md),
              onTapDown: (_) => _setPressed(true),
              onTapUp: (_) => _setPressed(false),
              // Cancel matters: a finger that slides off the card must not
              // leave it stuck in the pressed state.
              onTapCancel: () => _setPressed(false),
              // Fires the instant the tap registers, not after whatever screen
              // this opens finishes loading - a physical response tied to the
              // finger leaving the glass, same as every reference app (Blinkit
              // etc.) does on every tap, not just ones that end in a network call.
              onTap: onTap == null
                  ? null
                  : () {
                      HapticFeedback.selectionClick();
                      onTap();
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
                          // The product's own shadow, cast DOWN onto the card
                          // beneath it. This is the single effect that sells
                          // the whole thing: with the card carrying one shadow
                          // and the product carrying another, offset further
                          // and blurred wider, the product reads as an object
                          // resting on the card rather than a picture printed
                          // on it.
                          //
                          // Applied to the image's container rather than the
                          // image itself on purpose - a shadow that traced the
                          // product's silhouette would need an alpha-mask blur
                          // (saveLayer per card per frame). A rounded-rect
                          // shadow is one Skia draw call and, at this blur
                          // radius, indistinguishable in a 150px tile.
                          boxShadow: AppElevation.product,
                        ),
                        // The single most important picture in the shop. It
                        // is the reason GpNetworkImage defaults to contain:
                        // an atta bag cropped to fill a square stops being
                        // recognisable, which is the one job it has.
                        child: GpNetworkImage.fill(
                          url: variant?.imageUrl,
                          borderRadius: BorderRadius.circular(8),
                          fallbackIcon: Icons.shopping_basket_outlined,
                        ),
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
                          // The enclosing `if (onWishlistToggle != null)`
                          // already promotes this to non-null, so a second
                          // null check here was dead code the analyzer
                          // correctly flagged as always false.
                          onTap: hapticize(() {
                            HapticFeedback.lightImpact();
                            onWishlistToggle();
                          }),
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
                      width: 72,
                      child: quantityInCart > 0 && isInStock
                          ? _QuantityStepper(
                              quantity: quantityInCart,
                              onIncrement: onIncrement,
                              onDecrement: onDecrement,
                            )
                          : OutlinedButton(
                              // Stronger than the card/wishlist taps - this is
                              // the primary "yes, add this" action, so it gets
                              // a more deliberate thud instead of a light click.
                              onPressed: !isInStock || onAddPressed == null
                                  ? null
                                  : () {
                                      HapticFeedback.mediumImpact();
                                      onAddPressed();
                                    },
                              style: OutlinedButton.styleFrom(
                                // Teal, not blue: this is a basket action, and
                                // the palette reserves teal for the cart so
                                // "add to basket" looks the same everywhere it
                                // appears.
                                foregroundColor: AppColors.cart,
                                side: const BorderSide(color: AppColors.cart),
                                padding: EdgeInsets.zero,
                              ),
                              child: Text(isInStock ? 'ADD' : 'N/A', style: const TextStyle(fontSize: 12)),
                            ),
                    ),
                  ],
                ),
              ],
            ),
              ), // Padding
            ), // InkWell
          ), // Material
        ), // AnimatedContainer
      ), // AnimatedScale
    ); // RepaintBoundary
  }

  // Avoids showing "1.0 kg" - shows "1 kg" for whole numbers, "0.5 kg" otherwise.
  String _formatQuantity(double quantity) {
    return quantity == quantity.roundToDouble()
        ? quantity.toStringAsFixed(0)
        : quantity.toStringAsFixed(1);
  }
}

/// The - / n / + control shown once an item is in the cart.
///
/// Sized to match the ADD button it replaces so the card does not resize the
/// moment something is added - a card that changes height on tap makes the
/// whole grid jump under the customer's finger.
class _QuantityStepper extends StatelessWidget {
  const _QuantityStepper({required this.quantity, this.onIncrement, this.onDecrement});

  final int quantity;
  final VoidCallback? onIncrement;
  final VoidCallback? onDecrement;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.cart,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          _StepperButton(
            icon: quantity == 1 ? Icons.delete_outline : Icons.remove,
            onPressed: onDecrement,
          ),
          Text(
            '$quantity',
            style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 13),
          ),
          _StepperButton(icon: Icons.add, onPressed: onIncrement),
        ],
      ),
    );
  }
}

class _StepperButton extends StatelessWidget {
  const _StepperButton({required this.icon, this.onPressed});

  final IconData icon;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onPressed == null
          ? null
          : () {
              HapticFeedback.lightImpact();
              onPressed!();
            },
      // Padding rather than a smaller icon: the tap target stays finger-sized
      // even though the glyph is small, which matters in a two-column grid.
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 6),
        child: Icon(icon, size: 16, color: Colors.white),
      ),
    );
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
