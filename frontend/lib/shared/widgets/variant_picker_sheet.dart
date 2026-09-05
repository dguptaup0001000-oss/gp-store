import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/error_messages.dart';
import '../../core/theme/app_theme.dart';
import '../../features/cart/presentation/cart_providers.dart';
import '../../features/products/domain/product_models.dart';
import '../../features/products/presentation/products_providers.dart';

/// Choosing a pack size without leaving the grid.
///
/// WHY THIS EXISTS AT ALL. A browse, search or feed card carries exactly one
/// variant - the backend trims the rest so a twenty-product page does not
/// serialise a hundred prices no card draws. So tapping ADD on a product sold
/// in 500 g and 1 kg quietly added whichever one the server picked, and the
/// customer had no way to know the other existed short of opening the
/// product. This sheet is the missing step.
///
/// The full variant list is fetched HERE rather than being pushed into every
/// card of every feed page - one request when a customer actually asks, which
/// is the whole point of the trimming this works around.
Future<void> showVariantPicker(BuildContext context, Product product) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.white,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    builder: (context) => _VariantPickerSheet(product: product),
  );
}

class _VariantPickerSheet extends ConsumerWidget {
  const _VariantPickerSheet({required this.product});

  final Product product;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(productDetailProvider(product.id));

    return SafeArea(
      child: ConstrainedBox(
        // Never more than most of the screen, so the sheet is always
        // recognisably a sheet and the grid stays visible behind it.
        constraints: BoxConstraints(
          maxHeight: MediaQuery.of(context).size.height * 0.7,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
              child: Text(
                product.name,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: Text('Choose a size',
                  style: TextStyle(color: AppColors.textSecondary, fontSize: 13)),
            ),
            Flexible(
              child: detail.when(
                loading: () => const Padding(
                  padding: EdgeInsets.symmetric(vertical: 32),
                  child: Center(child: CircularProgressIndicator()),
                ),
                // FAILING LOUDLY MATTERS HERE. Falling back to the one variant
                // the card already had would silently put the customer back in
                // the situation this sheet exists to fix - they would tap the
                // only row and never learn the other sizes were unreachable.
                error: (error, _) => Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text("Couldn't load the sizes: ${extractErrorMessage(error)}"),
                      const SizedBox(height: 12),
                      OutlinedButton(
                        onPressed: () =>
                            ref.invalidate(productDetailProvider(product.id)),
                        child: const Text('Try again'),
                      ),
                    ],
                  ),
                ),
                data: (loaded) {
                  final variants = _ordered(loaded.variants);
                  if (variants.isEmpty) {
                    return const Padding(
                      padding: EdgeInsets.fromLTRB(16, 8, 16, 24),
                      child: Text('This product has no sizes for sale today.'),
                    );
                  }
                  return ListView.separated(
                    shrinkWrap: true,
                    padding: const EdgeInsets.only(bottom: 8),
                    itemCount: variants.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, index) =>
                        _VariantRow(variant: variants[index]),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// In-stock sizes first, then by pack size, so the row a customer can
  /// actually buy is never buried under three sold-out ones.
  ///
  /// A copy, never the model's own list: a freezed model deserialises with an
  /// UNMODIFIABLE list, and sorting in place throws.
  static List<ProductVariant> _ordered(List<ProductVariant> variants) {
    final sorted = variants.toList();
    sorted.sort((a, b) {
      if (a.available != b.available) return a.available ? -1 : 1;
      return (a.quantity ?? 0).compareTo(b.quantity ?? 0);
    });
    return sorted;
  }
}

class _VariantRow extends ConsumerWidget {
  const _VariantRow({required this.variant});

  final ProductVariant variant;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final line = ref.watch(cartLineForVariantProvider(variant.id));
    final quantity = line?.quantity ?? 0;

    final label = variant.quantity != null && variant.unit != null
        ? '${_formatQuantity(variant.quantity!)} ${variant.unit}'
        : 'One pack';

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label,
                    style: const TextStyle(
                        fontWeight: FontWeight.w600, fontSize: 15)),
                const SizedBox(height: 2),
                Row(
                  children: [
                    Text('₹${variant.sellingPrice.toStringAsFixed(0)}',
                        style: const TextStyle(
                            fontWeight: FontWeight.w700, fontSize: 15)),
                    if (variant.mrp != null &&
                        variant.mrp! > variant.sellingPrice) ...[
                      const SizedBox(width: 6),
                      Text(
                        '₹${variant.mrp!.toStringAsFixed(0)}',
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.textSecondary,
                          decoration: TextDecoration.lineThrough,
                        ),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),
          SizedBox(
            width: 96,
            height: 34,
            child: !variant.available
                ? const Center(
                    child: Text('Sold out',
                        style: TextStyle(
                            color: AppColors.textSecondary,
                            fontWeight: FontWeight.w600,
                            fontSize: 12)),
                  )
                : quantity == 0
                    ? OutlinedButton(
                        onPressed: () => _guard(context, () async {
                          HapticFeedback.mediumImpact();
                          await ref
                              .read(cartControllerProvider.notifier)
                              .addToCart(variantId: variant.id, quantity: 1);
                        }),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: AppColors.cart,
                          side: const BorderSide(color: AppColors.cart),
                          padding: EdgeInsets.zero,
                        ),
                        child: const Text('ADD',
                            style: TextStyle(
                                fontSize: 12, fontWeight: FontWeight.w600)),
                      )
                    : _SheetStepper(
                        quantity: quantity,
                        onDecrement: () => _guard(context, () async {
                          final cart = ref.read(cartControllerProvider.notifier);
                          // The cart has no meaning for a quantity of zero, so
                          // the last one removes the line - same rule as the
                          // card's stepper, hence the bin icon at 1.
                          if (quantity <= 1) {
                            await cart.removeItem(cartItemId: line!.cartItemId);
                          } else {
                            await cart.updateQuantity(
                                cartItemId: line!.cartItemId,
                                quantity: quantity - 1);
                          }
                        }),
                        onIncrement: () => _guard(context, () async {
                          await ref
                              .read(cartControllerProvider.notifier)
                              .updateQuantity(
                                  cartItemId: line!.cartItemId,
                                  quantity: quantity + 1);
                        }),
                      ),
          ),
        ],
      ),
    );
  }

  /// One error path for every action in the sheet, so a refused add surfaces
  /// the reason instead of the row simply not changing.
  Future<void> _guard(
      BuildContext context, Future<void> Function() action) async {
    try {
      await action();
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    }
  }

  static String _formatQuantity(double quantity) {
    return quantity == quantity.roundToDouble()
        ? quantity.toStringAsFixed(0)
        : quantity.toStringAsFixed(1);
  }
}

class _SheetStepper extends StatelessWidget {
  const _SheetStepper({
    required this.quantity,
    required this.onIncrement,
    required this.onDecrement,
  });

  final int quantity;
  final VoidCallback onIncrement;
  final VoidCallback onDecrement;

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
          _button(quantity == 1 ? Icons.delete_outline : Icons.remove, onDecrement),
          Text('$quantity',
              style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w700,
                  fontSize: 14)),
          _button(Icons.add, onIncrement),
        ],
      ),
    );
  }

  Widget _button(IconData icon, VoidCallback onPressed) {
    return InkWell(
      onTap: () {
        HapticFeedback.lightImpact();
        onPressed();
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 7),
        child: Icon(icon, size: 18, color: Colors.white),
      ),
    );
  }
}
