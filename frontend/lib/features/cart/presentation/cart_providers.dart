import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/cart_repository.dart';
import '../domain/cart_models.dart';

final cartRepositoryProvider = Provider<CartRepository>((ref) {
  return CartRepository(apiClient: ref.watch(apiClientProvider));
});

/// Single source of truth for cart contents app-wide - every screen that
/// shows a cart badge or the cart itself watches this same provider, so
/// adding an item from the product detail screen is instantly reflected in
/// the home screen's cart badge without a manual refresh anywhere.
class CartController extends AsyncNotifier<CartModel> {
  @override
  Future<CartModel> build() async {
    // Only fetch a real cart when logged in - an unauthenticated call would
    // correctly 401 anyway, so don't even try.
    final authState = ref.watch(authControllerProvider);
    if (authState.status != AuthStatus.authenticated) {
      return const CartModel();
    }
    return ref.read(cartRepositoryProvider).getMyCart();
  }

  Future<void> addToCart({required int variantId, required int quantity}) async {
    final repository = ref.read(cartRepositoryProvider);
    state = const AsyncLoading<CartModel>().copyWithPrevious(state);
    state = await AsyncValue.guard(() => repository.addToCart(variantId: variantId, quantity: quantity));
    // Instant physical confirmation the tap registered, independent of how
    // long the network call took - only fires once the add actually
    // succeeded, so a failed add doesn't falsely feel like it worked.
    if (state.hasValue) {
      HapticFeedback.lightImpact();
    }
  }

  Future<void> updateQuantity({required int cartItemId, required int quantity}) async {
    final repository = ref.read(cartRepositoryProvider);
    state = const AsyncLoading<CartModel>().copyWithPrevious(state);
    state = await AsyncValue.guard(
      () => repository.updateItemQuantity(cartItemId: cartItemId, quantity: quantity),
    );
    if (state.hasValue) {
      HapticFeedback.selectionClick();
    }
  }

  Future<void> removeItem({required int cartItemId}) async {
    final repository = ref.read(cartRepositoryProvider);
    state = const AsyncLoading<CartModel>().copyWithPrevious(state);
    state = await AsyncValue.guard(() => repository.removeItem(cartItemId: cartItemId));
    if (state.hasValue) {
      HapticFeedback.lightImpact();
    }
  }
}

final cartControllerProvider = AsyncNotifierProvider<CartController, CartModel>(CartController.new);
