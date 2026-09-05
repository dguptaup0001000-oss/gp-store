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
    this.onOptionsPressed,
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

  /// Opens the size chooser, for a product that comes in more than one pack.
  ///
  /// WHEN THIS IS SET THE STEPPER IS NOT SHOWN, and that is the whole design.
  /// A single +/- on the card can only mean one variant, so on a product with
  /// three sizes it would either increment whichever one happened to be in
  /// the cart or, worse, add a size the customer never chose. The button
  /// keeps saying what is in the basket and reopens the chooser instead, and
  /// the per-size steppers live in the sheet where each one is labelled.
  ///
  /// Null for a single-size product, which behaves exactly as it always has.
  final VoidCallback? onOptionsPressed;

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
    final onOptionsPressed = widget.onOptionsPressed;
    final hasOptions = onOptionsPressed != null;

    final variant = product.primaryVariant;
    final isInStock = variant?.available ?? false;
    final discount = product.discountPercent;

    final packSize = (variant?.quantity != null && variant?.unit != null)
        ? '${_formatQuantity(variant!.quantity!)} ${variant.unit}'
        : null;

    // Rides on the line that already exists rather than adding a row. The
    // card's height is derived arithmetic (see ProductGrid), and a new text
    // row would clip every grid in the app at larger text scales.
    final optionsLabel = hasOptions ? '${product.optionCount} options' : null;
    final packLabel = packSize != null && optionsLabel != null
        ? '$packSize \u00b7 $optionsLabel'
        : packSize ?? optionsLabel;

    // Built once, placed by whichever density branch below is active, so the
    // two layouts can never drift into behaving differently on the one
    // control that actually spends the customer's money.
    //
    // OUT OF STOCK IS A VISIBLE STATE, NOT JUST A DEAD TAP. A disabled
    // OutlinedButton inherits the theme's disabled colours, which on this
    // palette are close enough to the live teal to read as tappable - so a
    // shopper presses it, nothing happens, and the app looks broken rather
    // than the shelf looking empty. Explicit greys, an explicit grey border
    // and 'Sold out' say what is true. The wishlist heart above stays live.
    final Widget addControl = quantityInCart > 0 && isInStock && !hasOptions
        ? _QuantityStepper(
            quantity: quantityInCart,
            onIncrement: onIncrement,
            onDecrement: onDecrement,
          )
        : OutlinedButton(
            // Stronger than the card/wishlist taps - this is the primary
            // "yes, add this" action, so it gets a more deliberate thud
            // instead of a light click.
            onPressed: !isInStock || (onOptionsPressed ?? onAddPressed) == null
                ? null
                : () {
                    HapticFeedback.mediumImpact();
                    (onOptionsPressed ?? onAddPressed)!();
                  },
            style: OutlinedButton.styleFrom(
              // Teal, not blue: this is a basket action, and the palette
              // reserves teal for the cart so "add to basket" looks the same
              // everywhere it appears.
              foregroundColor: AppColors.cart,
              side: BorderSide(
                color: isInStock ? AppColors.cart : AppColors.textSecondary.withValues(alpha: 0.35),
              ),
              backgroundColor: Colors.white,
              disabledForegroundColor: AppColors.textSecondary.withValues(alpha: 0.55),
              padding: EdgeInsets.zero,
            ),
            child: Text(
              // "2 IN BAG" rather than a stepper: the count is honest about
              // the whole product, and the tap goes back to the chooser where
              // each size has its own control.
              !isInStock
                  ? 'Sold out'
                  : hasOptions && quantityInCart > 0
                      ? '$quantityInCart IN BAG'
                      : 'ADD',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
            ),
          );

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
              child: LayoutBuilder(builder: (context, constraints) {
            // ONE CARD, THREE DENSITIES. The grid went from two columns to
            // three, which takes a card on a 360dp phone from ~168dp wide to
            // ~110dp. Fixed type sizes and a 72dp ADD button do not survive
            // that, and forking the widget per grid would leave two cards to
            // keep in sync. So the card measures itself and adapts.
            //
            // The threshold is the card width at which the price and a
            // full-width ADD button stop fitting side by side.
            final compact = constraints.maxWidth < 140;
            final pad = compact ? 6.0 : 8.0;
            final nameSize = compact ? 12.0 : 13.0;
            final priceSize = compact ? 13.0 : 14.0;

            return Padding(
            padding: EdgeInsets.all(pad),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Stack(
                  clipBehavior: Clip.none,
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
                    // Pack size sits ON the image at three-up, where there is
                    // no room for it as its own text row below. Bottom-left,
                    // opposite the ADD button, so the two never collide.
                    if (compact && packLabel != null)
                      Positioned(
                        left: 2,
                        bottom: 2,
                        // right as well as left, so the chip has a WIDTH to
                        // ellipsis within. Without it "500 ml - 3 options" at
                        // a 1.3x text scale paints past the edge of the image.
                        // Align keeps it hugging its text at ordinary sizes.
                        right: 2,
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                            decoration: BoxDecoration(
                              color: Colors.white.withValues(alpha: 0.92),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Text(
                              packLabel,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 9,
                                fontWeight: FontWeight.w600,
                                color: AppColors.textSecondary,
                              ),
                            ),
                          ),
                        ),
                      ),
                    if (onWishlistToggle != null)
                      Positioned(
                        top: 2,
                        right: 2,
                        child: GestureDetector(
                          // The heart stays live even when the product is out
                          // of stock: saving something for when it is back is
                          // exactly what a shopper wants at that moment, and
                          // it is the only action still open to them here.
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
                        child: IgnorePointer(
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
                      ),
                    // AT THREE-UP THE ADD BUTTON OVERLAPS THE IMAGE. That is
                    // not decoration: it reclaims the ~34dp row the button
                    // used to occupy below the price, which is what lets a
                    // three-column card stay short enough to show the name
                    // and price without clipping. Blinkit does the same, for
                    // the same reason.
                    if (compact)
                      Positioned(
                        right: -2,
                        bottom: -10,
                        child: SizedBox(
                          height: 28,
                          width: 58,
                          child: addControl,
                        ),
                      ),
                  ],
                ),
                SizedBox(height: compact ? 14 : 8),
                Text(
                  product.name,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context)
                      .textTheme
                      .bodyLarge
                      ?.copyWith(fontSize: nameSize, fontWeight: FontWeight.w500),
                ),
                if (!compact && packLabel != null) ...[
                  const SizedBox(height: 2),
                  Text(
                    packLabel,
                    // One line, always. This row is part of the fixed info
                    // block ProductGrid's aspect ratio is derived from, so a
                    // label that wrapped would push the price off the card.
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11),
                  ),
                ],
                SizedBox(height: compact ? 4 : 6),
                Row(
                  children: [
                    Expanded(
                      child: variant == null
                          ? const SizedBox.shrink()
                          : Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Flexible(
                                  child: Text(
                                    '\u20b9${variant.sellingPrice.toStringAsFixed(0)}',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(fontWeight: FontWeight.w700, fontSize: priceSize),
                                  ),
                                ),
                                if (variant.mrp != null && variant.mrp! > variant.sellingPrice) ...[
                                  const SizedBox(width: 4),
                                  Flexible(
                                    child: Text(
                                      '\u20b9${variant.mrp!.toStringAsFixed(0)}',
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(
                                        fontSize: 11,
                                        color: AppColors.textSecondary,
                                        decoration: TextDecoration.lineThrough,
                                      ),
                                    ),
                                  ),
                                ],
                              ],
                            ),
                    ),
                    // At two-up the button keeps its old place beside the
                    // price; at three-up it has already been drawn over the
                    // image above, so nothing goes here.
                    if (!compact)
                      SizedBox(
                        height: 32,
                        width: 72,
                        child: addControl,
                      ),
                  ],
                ),
              ],
            ),
            );
          }),   // LayoutBuilder
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

  /// The same block on a compact (three-up) card. Shorter for two concrete
  /// reasons, not by taste: the ADD button is drawn over the image instead of
  /// occupying its own ~34px row, and the pack size moves onto the image as a
  /// chip instead of being its own text line. What remains below the image is
  /// the overhang gap, a two-line name, and the price row.
  static const double _compactInfoBlockAt1x = 78.0;

  /// Card padding, both sides - the image is inset by this within the card.
  static const double _cardPadding = 16.0;

  /// Compact cards use 6px padding per side rather than 8 (see ProductCard's
  /// LayoutBuilder), so the image is inset by less.
  static const double _compactCardPadding = 12.0;

  /// Where ProductCard switches to the compact layout. Kept here as well as
  /// there so the geometry and the widget cannot disagree about which layout
  /// a given width produces - a disagreement shows up as clipped text.
  static const double compactBelowWidth = 140.0;

  /// [availableWidth] is the width the GRID actually gets, when that is not
  /// the whole screen - the category browser puts a rail down the left, so its
  /// grid is ~90dp narrower than the window. Passing it matters more than it
  /// looks: the compact layout switches on measured card width, so a grid that
  /// reports the wrong width gets a ratio computed for the roomy layout while
  /// the card renders the compact one, and the difference comes out as clipped
  /// text at the bottom of every tile.
  static double aspectRatio(
    BuildContext context, {
    double columns = 2,
    double? availableWidth,
    double outerPadding = 24,
    double gap = 12,
  }) {
    final media = MediaQuery.of(context);
    final textScale = media.textScaler.scale(14) / 14;

    // Usable width per card: available width minus outer padding and gaps.
    final width = availableWidth ?? media.size.width;
    final gridWidth = width - outerPadding - (gap * (columns - 1));
    final cardWidth = gridWidth / columns;

    final compact = cardWidth < compactBelowWidth;
    final padding = compact ? _compactCardPadding : _cardPadding;
    final imageEdge = cardWidth - padding;
    final infoBlock = (compact ? _compactInfoBlockAt1x : _infoBlockAt1x) * textScale;
    final cardHeight = imageEdge + infoBlock + padding;

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
