import '../../../core/api/api_client.dart';
import '../domain/cart_models.dart';

class CartRepository {
  CartRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<CartModel> getMyCart() async {
    final response = await apiClient.dio.get('/api/carts/mine');
    return CartModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<CartModel> addToCart({required int variantId, required int quantity}) async {
    final response = await apiClient.dio.post(
      '/api/carts/add',
      queryParameters: {'variantId': variantId, 'quantity': quantity},
    );
    return CartModel.fromJson(response.data as Map<String, dynamic>);
  }

  /// Sets the EXACT quantity (not additive) - matches the backend's
  /// updateItemQuantity, which is what a +/- stepper actually needs.
  Future<CartModel> updateItemQuantity({required int cartItemId, required int quantity}) async {
    final response = await apiClient.dio.put(
      '/api/carts/items/$cartItemId',
      queryParameters: {'quantity': quantity},
    );
    return CartModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<CartModel> removeItem({required int cartItemId}) async {
    final response = await apiClient.dio.delete('/api/carts/items/$cartItemId');
    return CartModel.fromJson(response.data as Map<String, dynamic>);
  }
}
