import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_theme.dart';
import '../../features/cart/presentation/cart_providers.dart';
import '../../features/cart/presentation/cart_screen.dart';

/// The floating "N items · ₹total · View Cart" bar - drop this in as any
/// screen's Scaffold.bottomNavigationBar. Self-contained (reads the cart
/// itself via cartControllerProvider) so it reacts live the instant an item
/// is added anywhere in the app, without the caller needing to wire up
/// item count/total/navigation by hand. Was previously home_screen.dart's
/// private _HomeCartBar - extracted so every screen where a customer
/// actually adds items (search, category/brand browsing, product detail)
/// can show the same instant "you have items waiting" shortcut, not just
/// the home screen.
class CartSummaryBar extends ConsumerWidget {
  const CartSummaryBar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cart = ref.watch(cartControllerProvider).valueOrNull;
    final itemCount = cart?.totalItems ?? 0;
    final total = cart?.totalAmount ?? 0.0;

    if (itemCount <= 0) return const SizedBox.shrink();

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: Material(
          color: AppColors.primary,
          borderRadius: BorderRadius.circular(AppRadius.lg),
          elevation: 4,
          child: InkWell(
            borderRadius: BorderRadius.circular(AppRadius.lg),
            onTap: () {
              HapticFeedback.selectionClick();
              Navigator.of(context).push(MaterialPageRoute(builder: (_) => const CartScreen()));
            },
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              child: Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(AppRadius.sm),
                    ),
                    child: Text(
                      '$itemCount',
                      style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 13),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      '₹${total.toStringAsFixed(0)}',
                      style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 15),
                    ),
                  ),
                  const Text(
                    'View Cart',
                    style: TextStyle(color: Colors.white, fontWeight: FontWeight.w600, fontSize: 14),
                  ),
                  const SizedBox(width: 4),
                  const Icon(Icons.arrow_forward, color: Colors.white, size: 18),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
