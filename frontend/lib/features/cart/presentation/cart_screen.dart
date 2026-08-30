import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../address/domain/address_models.dart';
import '../../address/presentation/address_providers.dart';
import '../../checkout/presentation/checkout_screen.dart';
import '../domain/cart_models.dart';
import 'cart_providers.dart';
import '../../../core/images/gp_network_image.dart';
import '../../../core/util/haptic_widgets.dart';

class CartScreen extends ConsumerWidget {
  const CartScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cartAsync = ref.watch(cartControllerProvider);

    ref.listen(cartControllerProvider, (previous, next) {
      if (next.hasError && next.hasValue) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(extractErrorMessage(next.error!))),
        );
      }
    });

    return Scaffold(
      appBar: AppBar(title: const Text('My Cart')),
      body: cartAsync.when(
        skipError: true,
        skipLoadingOnReload: true,
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string. The last few failures here logged NO
              // backend query at all, same pattern as the New Arrivals bug
              // before its real cause was found - this had never been
              // upgraded to show the real error like the order screens were.
              Text("Couldn't load your cart: ${extractErrorMessage(error)}"),
              const SizedBox(height: 8),
              TextButton(
                onPressed: hapticize(() => ref.invalidate(cartControllerProvider)),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
        data: (cart) => _CartBody(cart: cart),
      ),
    );
  }
}

class _CartBody extends ConsumerWidget {
  const _CartBody({required this.cart});

  final CartModel cart;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (cart.items.isEmpty) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.shopping_cart_outlined, size: 64, color: AppColors.textSecondary),
            SizedBox(height: 12),
            Text('Your cart is empty', style: TextStyle(color: AppColors.textSecondary)),
          ],
        ),
      );
    }

    return Column(
      children: [
        Expanded(
          child: ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: cart.items.length,
            separatorBuilder: (_, __) => const SizedBox(height: 12),
            itemBuilder: (context, index) => _CartItemTile(item: cart.items[index]),
          ),
        ),
        _CartSummary(cart: cart),
      ],
    );
  }
}

class _CartItemTile extends ConsumerWidget {
  const _CartItemTile({required this.item});

  final CartItemModel item;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isAvailable = item.available ?? true;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(8)),
            child: GpNetworkImage(
              url: item.imageUrl,
              renderWidth: 56,
              borderRadius: BorderRadius.circular(8),
              fallbackIcon: Icons.shopping_basket_outlined,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  item.productName ?? 'Product',
                  style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
                ),
                if (item.variantQuantity != null && item.unit != null)
                  Text(
                    '${item.variantQuantity} ${item.unit}',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12),
                  ),
                if (!isAvailable)
                  const Padding(
                    padding: EdgeInsets.only(top: 4),
                    child: Text(
                      'No longer available',
                      style: TextStyle(color: AppColors.error, fontSize: 12, fontWeight: FontWeight.w600),
                    ),
                  ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Text('₹${item.totalPrice.toStringAsFixed(0)}',
                        style: const TextStyle(fontWeight: FontWeight.w700)),
                    if (item.mrp != null && item.mrp! > item.price)
                      Padding(
                        padding: const EdgeInsets.only(left: 6),
                        child: Text(
                          '₹${(item.mrp! * item.quantity).toStringAsFixed(0)}',
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppColors.textSecondary,
                            decoration: TextDecoration.lineThrough,
                          ),
                        ),
                      ),
                    const Spacer(),
                    _QuantityStepper(item: item),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _QuantityStepper extends ConsumerWidget {
  const _QuantityStepper({required this.item});

  final CartItemModel item;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.primary),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            icon: const Icon(Icons.remove, size: 16),
            color: AppColors.primary,
            constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
            padding: EdgeInsets.zero,
            tooltip: item.quantity <= 1 ? 'Remove from cart' : 'Decrease quantity',
            onPressed: hapticize(() {
              HapticFeedback.selectionClick();
              final newQuantity = item.quantity - 1;
              if (newQuantity <= 0) {
                ref.read(cartControllerProvider.notifier).removeItem(cartItemId: item.cartItemId);
              } else {
                ref
                    .read(cartControllerProvider.notifier)
                    .updateQuantity(cartItemId: item.cartItemId, quantity: newQuantity);
              }
            }),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Text('${item.quantity}', style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
          IconButton(
            icon: const Icon(Icons.add, size: 16),
            color: AppColors.primary,
            constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
            padding: EdgeInsets.zero,
            tooltip: 'Increase quantity',
            onPressed: hapticize(() {
              HapticFeedback.selectionClick();
              ref
                  .read(cartControllerProvider.notifier)
                  .updateQuantity(cartItemId: item.cartItemId, quantity: item.quantity + 1);
            }),
          ),
        ],
      ),
    );
  }
}

/// ConsumerWidget rather than StatelessWidget purely so the checkout button
/// can warm the address list before navigating - see the onPressed below.
class _CartSummary extends ConsumerWidget {
  const _CartSummary({required this.cart});

  final CartModel cart;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final savings = cart.items.fold<double>(0, (sum, item) =>
        item.mrp != null && item.mrp! > item.price
            ? sum + (item.mrp! - item.price) * item.quantity
            : sum);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('${cart.totalItems} items', style: Theme.of(context).textTheme.bodyMedium),
                      if (savings > 0)
                        Text(
                          'You saved ₹${savings.toStringAsFixed(0)}',
                          style: const TextStyle(color: AppColors.success, fontSize: 12, fontWeight: FontWeight.w600),
                        ),
                    ],
                  ),
                Text('₹${cart.totalAmount.toStringAsFixed(0)}',
                    style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
              ],
            ),
            if (cart.items.any((item) => item.available == false)) ...[
              const Text(
                'Remove unavailable items before checkout.',
                style: TextStyle(color: AppColors.error, fontSize: 12, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
            ],
            const SizedBox(height: 12),
            FilledButton(
              onPressed: cart.items.any((item) => item.available == false)
                  ? null
                  : hapticize(() {
                HapticFeedback.mediumImpact();

                // Start loading the address list BEFORE navigating. Checkout
                // needs it to auto-select a delivery address, and it used to
                // request it in its own initState - so the customer watched
                // an empty checkout screen for one round trip, and only then
                // did the checkout-preview request begin. Kicking it off here
                // overlaps that fetch with the navigation animation, and
                // myAddressesProvider's keep-alive window means checkout
                // finds it already resolved instead of asking again.
                //
                // Fire-and-forget on purpose: navigation must not wait on it.
                // If it fails, checkout falls back to its existing behaviour
                // of fetching and showing its own error.
                ref.read(myAddressesProvider.future).catchError(
                      (Object _) => <AddressModel>[],
                    );

                Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const CheckoutScreen()),
                );
              }),
              child: const Text('Proceed to Checkout'),
            ),
          ],
        ),
      ),
    );
  }
}
