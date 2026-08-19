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
  // Cart screen buttons (grid "Add", quantity +/-) don't manage their own
  // loading state - a rapid double-tap or a stepper mashed while a slow
  // network call is in flight would otherwise fire overlapping mutation
  // requests against the same cart, racing each other and duplicating
  // network traffic. Since this controller is a single app-wide instance
  // (not autoDispose), one flag here serializes every cart mutation for
  // the whole app cheaply, without threading loading state through every
  // call site (ProductCard, the stepper, etc).
  bool _mutationInFlight = false;

  bool get isMutating => _mutationInFlight;

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
    if (_mutationInFlight) return;
    _mutationInFlight = true;
    try {
      final repository = ref.read(cartRepositoryProvider);
      state = const AsyncLoading<CartModel>().copyWithPrevious(state);
      state = await AsyncValue.guard(() => repository.addToCart(variantId: variantId, quantity: quantity));
      // Instant physical confirmation the tap registered, independent of how
      // long the network call took - only fires once the add actually
      // succeeded, so a failed add doesn't falsely feel like it worked.
      if (state.hasValue) {
        HapticFeedback.lightImpact();
      }
    } finally {
      _mutationInFlight = false;
    }
  }

  Future<void> updateQuantity({required int cartItemId, required int quantity}) async {
    if (_mutationInFlight) return;
    _mutationInFlight = true;
    try {
      final repository = ref.read(cartRepositoryProvider);
      state = const AsyncLoading<CartModel>().copyWithPrevious(state);
      state = await AsyncValue.guard(
        () => repository.updateItemQuantity(cartItemId: cartItemId, quantity: quantity),
      );
      if (state.hasValue) {
        HapticFeedback.selectionClick();
      }
    } finally {
      _mutationInFlight = false;
    }
  }

  Future<void> removeItem({required int cartItemId}) async {
    if (_mutationInFlight) return;
    _mutationInFlight = true;
    try {
      final repository = ref.read(cartRepositoryProvider);
      state = const AsyncLoading<CartModel>().copyWithPrevious(state);
      state = await AsyncValue.guard(() => repository.removeItem(cartItemId: cartItemId));
      if (state.hasValue) {
        HapticFeedback.lightImpact();
      }
    } finally {
      _mutationInFlight = false;
    }
  }
}

final cartControllerProvider = AsyncNotifierProvider<CartController, CartModel>(CartController.new);
