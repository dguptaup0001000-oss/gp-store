import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/cart/domain/cart_grouping.dart';
import 'package:gpstore/features/cart/domain/cart_models.dart';

CartItemModel _line(int id, {int? shopId, double total = 100}) => CartItemModel(
      cartItemId: id,
      quantity: 1,
      price: total,
      totalPrice: total,
      shopId: shopId,
    );

void main() {
  group('groupCartByShop', () {
    test('a single-shop basket is exactly one group', () {
      // §2: Shop #1's customers must see no change at all. The screen keys
      // off isSplitAcrossShops and renders the flat list it always did.
      final cart = CartModel(
        items: [_line(1, shopId: 1), _line(2, shopId: 1)],
        shops: const [CartShopRef(shopId: 1, shopName: 'GP Store')],
      );

      final groups = groupCartByShop(cart);

      expect(groups, hasLength(1));
      expect(groups.single.items, hasLength(2));
      expect(isSplitAcrossShops(cart), isFalse);
    });

    test('a basket from two shops splits, keeping the order lines arrived in', () {
      final cart = CartModel(
        items: [
          _line(1, shopId: 7, total: 50),
          _line(2, shopId: 9, total: 30),
          _line(3, shopId: 7, total: 20),
        ],
        shops: const [
          CartShopRef(shopId: 7, shopName: 'Sharma Kirana'),
          CartShopRef(shopId: 9, shopName: 'Verma Store'),
        ],
      );

      final groups = groupCartByShop(cart);

      expect(groups.map((g) => g.shopId).toList(), [7, 9],
          reason: 'a stable order matters - regrouping between refreshes '
              'reads as the basket having changed when nothing has');
      expect(groups.first.items, hasLength(2));
      expect(groups.first.subtotal, 70);
      expect(groups.first.itemCount, 2);
      expect(groups.last.shopName, 'Verma Store');
      expect(isSplitAcrossShops(cart), isTrue);
    });

    test('a line saved before baskets carried a shop is kept, not dropped', () {
      final cart = CartModel(items: [_line(1), _line(2, shopId: 3)]);

      final groups = groupCartByShop(cart);

      expect(groups, hasLength(2));
      expect(groups.first.shopId, isNull);
      expect(groups.first.shopName, isNull,
          reason: 'an unlabelled group is better than a basket that quietly '
              'loses an item');
    });

    test('an id with no name is grouped anyway - the label is the screen\'s job', () {
      // An older backend sends ids on the lines and no shops list. Grouping
      // still works; only the labels are missing.
      final cart = CartModel(items: [_line(1, shopId: 4), _line(2, shopId: 5)]);

      final groups = groupCartByShop(cart);

      expect(groups, hasLength(2));
      expect(groups.every((g) => g.shopName == null), isTrue);
      expect(isSplitAcrossShops(cart), isTrue);
    });

    test('an empty basket has no groups and is not split', () {
      const cart = CartModel();
      expect(groupCartByShop(cart), isEmpty);
      expect(isSplitAcrossShops(cart), isFalse);
    });
  });
}
