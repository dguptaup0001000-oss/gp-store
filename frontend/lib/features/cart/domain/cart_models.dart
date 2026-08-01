import 'package:freezed_annotation/freezed_annotation.dart';

part 'cart_models.freezed.dart';
part 'cart_models.g.dart';

/// Mirrors backend's CartResponse.CartItemResponse exactly - a flat DTO with
/// product name/brand already included server-side, NOT a nested
/// productVariant object. This exists because the raw ProductVariant entity
/// deliberately excludes its parent product (prevents a serialization
/// recursion bug) - the backend fills that gap explicitly rather than the
/// app having to guess at a product name that was never sent.
@freezed
class CartItemModel with _$CartItemModel {
  const factory CartItemModel({
    required int cartItemId,
    int? productId,
    String? productName,
    String? productBrand,
    int? variantId,
    required int quantity,
    double? variantQuantity,
    String? unit,
    String? imageUrl,
    required double price,
    required double totalPrice,
    double? mrp,
    bool? available,
  }) = _CartItemModel;

  factory CartItemModel.fromJson(Map<String, dynamic> json) => _$CartItemModelFromJson(json);
}

@freezed
class CartModel with _$CartModel {
  const factory CartModel({
    int? cartId,
    @Default([]) List<CartItemModel> items,
    @Default(0) double totalAmount,
    @Default(0) int totalItems,
  }) = _CartModel;

  factory CartModel.fromJson(Map<String, dynamic> json) => _$CartModelFromJson(json);
}
