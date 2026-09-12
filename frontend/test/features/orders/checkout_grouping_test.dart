import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/orders/domain/order_group_models.dart';
import 'package:gpstore/features/orders/presentation/order_group_providers.dart';

OrderGroupSummary checkout(int id, List<int> orderIds) {
  return OrderGroupSummary(
    id: id,
    groupNumber: 'GRP-$id',
    shopCount: orderIds.length,
    shopOrders: [
      for (final orderId in orderIds)
        ShopOrderView(orderId: orderId, shopId: orderId * 10),
    ],
  );
}

void main() {
  group('which orders are half of a bigger checkout', () {
    test('a one-shop checkout is not offered a group screen', () {
      final container = ProviderContainer(overrides: [
        myCheckoutsProvider.overrideWith((ref) async => [checkout(1, [100])]),
      ]);
      addTearDown(container.dispose);

      return container.read(myCheckoutsProvider.future).then((_) {
        expect(container.read(checkoutForOrderProvider), isEmpty,
            reason: 'ONE ORDER IS THE WHOLE CHECKOUT. Offering "part of a '
                '1-shop checkout" on a single-shop order is a screen that '
                'explains something that did not happen (§2).');
      });
    });

    test('both halves of a two-shop checkout point at the same group', () {
      final container = ProviderContainer(overrides: [
        myCheckoutsProvider
            .overrideWith((ref) async => [checkout(9, [100, 101])]),
      ]);
      addTearDown(container.dispose);

      return container.read(myCheckoutsProvider.future).then((_) {
        final byOrder = container.read(checkoutForOrderProvider);
        expect(byOrder.keys.toSet(), {100, 101});
        expect(byOrder[100]!.id, 9);
        expect(byOrder[101]!.id, 9);
      });
    });

    test('an order from another checkout is not dragged in', () {
      final container = ProviderContainer(overrides: [
        myCheckoutsProvider.overrideWith((ref) async => [
              checkout(9, [100, 101]),
              checkout(10, [200]),
            ]),
      ]);
      addTearDown(container.dispose);

      return container.read(myCheckoutsProvider.future).then((_) {
        expect(container.read(checkoutForOrderProvider).containsKey(200),
            isFalse);
      });
    });
  });
}
