import 'package:freezed_annotation/freezed_annotation.dart';

import '../../products/domain/product_models.dart';

part 'wishlist_models.freezed.dart';
part 'wishlist_models.g.dart';

/// Mirrors backend's Wishlist entity - customer hidden server-side (same
/// reasoning as Review), product is the existing Product model.
@freezed
class WishlistItem with _$WishlistItem {
  const factory WishlistItem({
    required int id,
    Product? product,
  }) = _WishlistItem;

  factory WishlistItem.fromJson(Map<String, dynamic> json) => _$WishlistItemFromJson(json);
}
