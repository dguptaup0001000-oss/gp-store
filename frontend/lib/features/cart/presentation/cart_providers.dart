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

    final previous = state.valueOrNull;
    try {
      final repository = ref.read(cartRepositoryProvider);

      // Optimistic ONLY when this variant is already in the cart, because
      // that is the only case where everything needed to render the line is
      // already known - price, name, image. For a genuinely new item the
      // client has none of that (the product screen's model and the cart's
      // are different shapes), and fabricating a placeholder row that then
      // shifts as the real data arrives looks worse than a brief wait.
      // Predict only what can be predicted exactly.
      // Written as an explicit loop rather than .firstOrNull: that
      // extension lives in package:collection, which this project does not
      // depend on, and dart:core's Iterable has no null-safe first.
      CartItemModel? existing;
      for (final item in previous?.items ?? const <CartItemModel>[]) {
        if (item.variantId == variantId) {
          existing = item;
          break;
        }
      }
      if (previous != null && existing != null) {
        state = AsyncData(_withQuantity(previous, existing.cartItemId, existing.quantity + quantity));
      }

      final result = await repository.addToCart(variantId: variantId, quantity: quantity);
      state = AsyncData(result);
      // Physical confirmation the tap registered. Fires on success only, so
      // a failed add never feels like it worked.
      HapticFeedback.lightImpact();
    } catch (error, stackTrace) {
      _rollback(previous, error, stackTrace);
    } finally {
      _mutationInFlight = false;
    }
  }

  Future<void> updateQuantity({required int cartItemId, required int quantity}) async {
    if (_mutationInFlight) return;
    _mutationInFlight = true;

    final previous = state.valueOrNull;
    try {
      final repository = ref.read(cartRepositoryProvider);

      // Optimistic: the stepper moves NOW, not after a network round trip.
      // Tapping + and watching the number sit still until the server answers
      // is what made the cart feel slow even when the request itself was
      // fine - the delay was entirely in waiting to render a change we can
      // predict exactly.
      //
      // Note this replaces the AsyncLoading state that used to be set here.
      // Showing a spinner over a cart whose new contents we already know is
      // strictly worse than showing the new contents.
      if (previous != null) {
        state = AsyncData(_withQuantity(previous, cartItemId, quantity));
      }

      final result = await repository.updateItemQuantity(cartItemId: cartItemId, quantity: quantity);

      // The server's cart is authoritative and replaces the prediction -
      // prices, availability and totals are all recomputed server-side, so
      // any drift is corrected here rather than persisting in the UI.
      state = AsyncData(result);
      HapticFeedback.selectionClick();
    } catch (error, stackTrace) {
      _rollback(previous, error, stackTrace);
    } finally {
      _mutationInFlight = false;
    }
  }

  Future<void> removeItem({required int cartItemId}) async {
    if (_mutationInFlight) return;
    _mutationInFlight = true;

    final previous = state.valueOrNull;
    try {
      final repository = ref.read(cartRepositoryProvider);

      if (previous != null) {
        state = AsyncData(_withItemRemoved(previous, cartItemId));
      }

      final result = await repository.removeItem(cartItemId: cartItemId);
      state = AsyncData(result);
      HapticFeedback.lightImpact();
    } catch (error, stackTrace) {
      _rollback(previous, error, stackTrace);
    } finally {
      _mutationInFlight = false;
    }
  }

  /// Puts the cart back exactly as it was and surfaces the failure.
  ///
  /// The rollback is the half that makes optimistic UI honest: without it a
  /// failed request leaves the screen showing a quantity the server never
  /// accepted, and the customer only finds out at checkout. The previous
  /// value is attached to the error state so widgets reading valueOrNull
  /// keep rendering a real cart instead of blanking out - which is an
  /// improvement on the old behaviour, where a failed mutation replaced the
  /// state with a bare error and the cart disappeared.
  void _rollback(CartModel? previous, Object error, StackTrace stackTrace) {
    final rolledBack = AsyncError<CartModel>(error, stackTrace);
    state = previous == null ? rolledBack : rolledBack.copyWithPrevious(AsyncData(previous));
  }

  /// Applies a quantity change locally, mirroring what the server will do:
  /// the line total follows the unit price, and the cart totals follow the
  /// lines. Quantities of zero or less remove the line, matching
  /// CartService.updateItemQuantity's own behaviour rather than inventing a
  /// different rule on the client.
  CartModel _withQuantity(CartModel cart, int cartItemId, int quantity) {
    if (quantity <= 0) {
      return _withItemRemoved(cart, cartItemId);
    }
    final items = cart.items
        .map((item) => item.cartItemId == cartItemId
            ? item.copyWith(quantity: quantity, totalPrice: item.price * quantity)
            : item)
        .toList();
    return _withTotals(cart, items);
  }

  CartModel _withItemRemoved(CartModel cart, int cartItemId) {
    final items = cart.items.where((item) => item.cartItemId != cartItemId).toList();
    return _withTotals(cart, items);
  }

  /// Recomputes the denormalized totals the cart badge and summary bar read.
  /// Without this the line would update while the header still showed the
  /// old count - a worse inconsistency than simply waiting would have been.
  CartModel _withTotals(CartModel cart, List<CartItemModel> items) {
    return cart.copyWith(
      items: items,
      totalItems: items.fold<int>(0, (sum, item) => sum + item.quantity),
      totalAmount: items.fold<double>(0, (sum, item) => sum + item.totalPrice),
    );
  }
}

final cartControllerProvider = AsyncNotifierProvider<CartController, CartModel>(CartController.new);
