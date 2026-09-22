import '../../../core/api/api_client.dart';
import '../domain/wishlist_models.dart';

class WishlistRepository {
  WishlistRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<WishlistItem>> getMyWishlist() async {
    final response = await apiClient.dio.get('/api/wishlists/mine');
    return (response.data as List).map((e) => WishlistItem.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<WishlistItem> addToWishlist(int productId) async {
    final response = await apiClient.dio.post('/api/wishlists', data: {
      // WishlistRequest deliberately accepts only this scalar. Ownership is
      // taken from the authenticated principal; posting an entity-shaped
      // `product` object is rejected by validation and was why the heart
      // appeared to work locally but nothing was ever persisted.
      'productId': productId,
    });
    return WishlistItem.fromJson(response.data as Map<String, dynamic>);
  }

  /// Takes the WISHLIST ITEM's own id, not the product id - the backend has
  /// no "remove by product" endpoint, only "remove by wishlist entry".
  Future<void> removeFromWishlist(int wishlistItemId) async {
    await apiClient.dio.delete('/api/wishlists/$wishlistItemId');
  }
}
