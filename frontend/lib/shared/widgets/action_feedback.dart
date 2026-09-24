import 'package:flutter/material.dart';

import '../../features/cart/presentation/cart_screen.dart';
import '../../features/wishlist/presentation/wishlist_screen.dart';

void showAddedToCartFeedback(BuildContext context, String itemName) {
  _showAction(context,
      message: '$itemName added to cart', action: 'VIEW CART',
      onPressed: () => Navigator.of(context).push(
          MaterialPageRoute<void>(builder: (_) => const CartScreen())));
}

void showWishlistFeedback(BuildContext context, {required bool added}) {
  if (added) {
    _showAction(context, message: 'Added to wishlist', action: 'VIEW WISHLIST',
        onPressed: () => Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => const WishlistScreen())));
  } else {
    _showAction(context, message: 'Removed from wishlist');
  }
}

void showActionFailure(BuildContext context, String message) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(content: Text(message), duration: const Duration(seconds: 4)),
  );
}

void _showAction(BuildContext context, {required String message, String? action,
  VoidCallback? onPressed}) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
    duration: const Duration(seconds: 2),
    content: Row(children: [
      const Icon(Icons.check_circle_outline, color: Colors.white, size: 20),
      const SizedBox(width: 8),
      Expanded(child: Text(message)),
    ]),
    action: action == null ? null : SnackBarAction(label: action, onPressed: onPressed!),
  ));
}
