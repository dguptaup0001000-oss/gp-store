import 'cart_models.dart';

/// One shop's part of a basket.
///
/// A VIEW OF WHAT THE SERVER ALREADY DECIDED, not a decision. Every line
/// arrives carrying the shop it came off the shelf of - `cart_items.shop_id`,
/// stamped server-side at add-to-cart time and never sent by this app - and
/// this only puts the lines that share one next to each other.
class CartShopGroup {
  const CartShopGroup({
    required this.shopId,
    required this.shopName,
    required this.items,
  });

  /// Null only for a line saved before baskets carried a shop at all. Such a
  /// line is still shown; it is grouped on its own rather than dropped,
  /// because a basket that quietly loses an item is worse than one with an
  /// unlabelled group in it.
  final int? shopId;

  /// Null when the server sent an id with no name - the group is then labelled
  /// by the screen, not by inventing a name here.
  final String? shopName;

  final List<CartItemModel> items;

  double get subtotal =>
      items.fold<double>(0, (sum, item) => sum + item.totalPrice);

  int get itemCount =>
      items.fold<int>(0, (sum, item) => sum + item.quantity);
}

/// Splits a basket into one group per shop, in the order the shops first
/// appear.
///
/// STABLE ORDER, deliberately. Grouping by a map with no ordering would let
/// the two halves of a basket swap places between refreshes, which reads as
/// the basket having changed when nothing has.
///
/// THE SINGLE-SHOP CASE IS EXACTLY ONE GROUP, and callers are expected to
/// render that case as they always did - see `isSplitAcrossShops`. Shop #1's
/// customers must not be shown a grouping UI for a concept their deployment
/// does not have (§2).
List<CartShopGroup> groupCartByShop(CartModel cart) {
  final names = <int, String?>{
    for (final shop in cart.shops) shop.shopId: shop.shopName,
  };

  final order = <int?>[];
  final byShop = <int?, List<CartItemModel>>{};
  for (final item in cart.items) {
    final key = item.shopId;
    if (!byShop.containsKey(key)) {
      byShop[key] = <CartItemModel>[];
      order.add(key);
    }
    byShop[key]!.add(item);
  }

  return [
    for (final key in order)
      CartShopGroup(
        shopId: key,
        shopName: key == null ? null : names[key],
        items: List.unmodifiable(byShop[key]!),
      ),
  ];
}

/// Whether this basket will become more than one order at checkout.
///
/// The question a screen actually asks. A basket with lines from one shop -
/// every basket in a single-shop deployment - answers false, and the cart
/// renders exactly as it did before this existed.
bool isSplitAcrossShops(CartModel cart) {
  final distinct = <int?>{for (final item in cart.items) item.shopId};
  return distinct.length > 1;
}
