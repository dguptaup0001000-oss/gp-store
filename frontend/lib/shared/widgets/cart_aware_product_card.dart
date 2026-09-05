import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/presentation/auth_providers.dart';
import '../../features/cart/presentation/cart_providers.dart';
import '../../features/products/domain/product_models.dart';
import '../../features/wishlist/presentation/wishlist_providers.dart';
import 'product_card.dart';
import 'variant_picker_sheet.dart';

/// ProductCard with cart and wishlist state already wired.
///
/// NOT a second product card - it composes the one in product_card.dart and
/// adds nothing visual. It exists because every screen showing products was
/// repeating the same block: read the cart, find this variant's line, call
/// addToCart or updateQuantity or removeItem, show a snackbar on failure.
/// Five copies of that is five places for the quantity stepper to behave
/// slightly differently, and five places to fix when the cart API changes.
///
/// ProductCard itself stays presentational and Riverpod-free, which is what
/// keeps it testable; this is the one place that binds it to state.
class CartAwareProductCard extends ConsumerWidget {
  const CartAwareProductCard({super.key, required this.product, this.onTap});

  final Product product;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final variant = product.primaryVariant;

    // Watched so the heart reflects toggles from anywhere - product detail,
    // wishlist screen, another card showing the same product.
    ref.watch(wishlistControllerProvider);
    final wishlist = ref.read(wishlistControllerProvider.notifier);

    // MORE THAN ONE PACK SIZE CHANGES WHAT THE CARD CAN HONESTLY DO. The card
    // carries a single variant by design (the server trims the rest so a feed
    // page stays small), so ADD used to put whichever size the server picked
    // into the basket without ever telling the customer the others existed.
    // For these products the button opens the size chooser instead, and the
    // count it shows is the whole product's - a +/- on the card could only
    // ever mean one size and would be wrong the moment two are in the basket.
    if (product.optionCount > 1) {
      return ProductCard(
        product: product,
        onTap: onTap,
        isWishlisted: wishlist.isWishlisted(product.id),
        onWishlistToggle: () => wishlist.toggle(product.id),
        quantityInCart: ref.watch(cartQuantityForProductProvider(product.id)),
        onOptionsPressed: () => showVariantPicker(context, product),
      );
    }

    // Only this variant's line, so adding an unrelated product does not
    // rebuild every card on screen.
    final line = variant == null ? null : ref.watch(cartLineForVariantProvider(variant.id));

    return ProductCard(
      product: product,
      onTap: onTap,
      isWishlisted: wishlist.isWishlisted(product.id),
      onWishlistToggle: () => wishlist.toggle(product.id),
      quantityInCart: line?.quantity ?? 0,
      onAddPressed: variant == null ? null : () => _guard(context, ref, () async {
            await ref.read(cartControllerProvider.notifier)
                .addToCart(variantId: variant.id, quantity: 1);
          }),
      onIncrement: line == null ? null : () => _guard(context, ref, () async {
            await ref.read(cartControllerProvider.notifier)
                .updateQuantity(cartItemId: line.cartItemId, quantity: line.quantity + 1);
          }),
      onDecrement: line == null ? null : () => _guard(context, ref, () async {
            // Decrementing the last one removes the line rather than trying to
            // store a quantity of zero, which the cart has no meaning for -
            // hence the stepper showing a bin icon at 1.
            if (line.quantity <= 1) {
              await ref.read(cartControllerProvider.notifier).removeItem(cartItemId: line.cartItemId);
            } else {
              await ref.read(cartControllerProvider.notifier)
                  .updateQuantity(cartItemId: line.cartItemId, quantity: line.quantity - 1);
            }
          }),
    );
  }

  /// One error path for every cart action, so a failed tap always surfaces
  /// the same way instead of silently doing nothing on some screens.
  Future<void> _guard(BuildContext context, WidgetRef ref, Future<void> Function() action) async {
    try {
      await action();
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }
}
