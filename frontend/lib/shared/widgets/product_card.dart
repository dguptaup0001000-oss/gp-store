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
                            color: AppColors.primary,
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
                  ],
                ),
                const SizedBox(height: 6),
                SizedBox(
                  width: double.infinity,
                  height: 32,
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
                    child: Text(isInStock ? 'ADD' : 'Unavailable', style: const TextStyle(fontSize: 12)),
                  ),
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
