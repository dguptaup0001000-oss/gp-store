import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/wishlist_repository.dart';
import '../domain/wishlist_models.dart';

final wishlistRepositoryProvider = Provider<WishlistRepository>((ref) {
  return WishlistRepository(apiClient: ref.watch(apiClientProvider));
});

/// Single source of truth for wishlist contents - same reasoning as
/// CartController: a toggle from a product card, search results, or the
/// detail screen all need to reflect the same state everywhere instantly.
class WishlistController extends AsyncNotifier<List<WishlistItem>> {
  @override
  Future<List<WishlistItem>> build() async {
    final authState = ref.watch(authControllerProvider);
    if (authState.status != AuthStatus.authenticated) {
      return [];
    }
    return ref.read(wishlistRepositoryProvider).getMyWishlist();
  }

  bool isWishlisted(int productId) {
    return state.valueOrNull?.any((item) => item.product?.id == productId) ?? false;
  }

  /// Adds if not already wishlisted, removes if it is - the one call a
  /// heart-icon button actually needs, hiding the add-vs-remove-by-id
  /// distinction from every call site.
  Future<void> toggle(int productId) async {
    final current = state.valueOrNull ?? [];
    WishlistItem? existing;
    for (final item in current) {
      if (item.product?.id == productId) {
        existing = item;
        break;
      }
    }

    final repository = ref.read(wishlistRepositoryProvider);
    state = const AsyncLoading<List<WishlistItem>>().copyWithPrevious(state);

    state = await AsyncValue.guard(() async {
      if (existing != null) {
        await repository.removeFromWishlist(existing.id);
      } else {
        await repository.addToWishlist(productId);
      }
      return repository.getMyWishlist();
    });
  }
}

final wishlistControllerProvider = AsyncNotifierProvider<WishlistController, List<WishlistItem>>(
  WishlistController.new,
);
