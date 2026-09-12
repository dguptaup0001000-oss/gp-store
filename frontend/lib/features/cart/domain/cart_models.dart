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

    /// Which shop this line came off the shelf of.
    ///
    /// Sent by the backend (CartResponse.CartItemResponse.shopId), never
    /// chosen here. Nullable for a line saved before baskets carried a shop.
    int? shopId,
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

    /// The shops this basket spans, named by the server.
    ///
    /// Empty on an older backend, which is fine: the ids are on the lines, so
    /// grouping still works and only the labels are missing.
    @Default([]) List<CartShopRef> shops,
  }) = _CartModel;

  factory CartModel.fromJson(Map<String, dynamic> json) => _$CartModelFromJson(json);
}

/// One shop a basket has lines from, as `CartResponse.CartShop` sends it.
@freezed
class CartShopRef with _$CartShopRef {
  const factory CartShopRef({
    required int shopId,
    String? shopName,
  }) = _CartShopRef;

  factory CartShopRef.fromJson(Map<String, dynamic> json) => _$CartShopRefFromJson(json);
}
