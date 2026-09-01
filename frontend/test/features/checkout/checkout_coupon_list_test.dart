import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/checkout/presentation/checkout_coupon_list.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';

/// The offers list at checkout.
///
/// The property that matters most here is the one that is easiest to get
/// wrong: every condition shown under an offer must come from a stored field.
/// A shopper who reads "Minimum order value ₹299" and is then refused at ₹250
/// has been misled by the app, and the only way that cannot happen is if these
/// strings and CouponService's validation read the same numbers.
void main() {
  Widget host(List<Coupon> offers, TextEditingController controller,
      {void Function(String)? onApply, VoidCallback? onRemove}) {
    return ProviderScope(
      overrides: [
        activeOffersProvider.overrideWith((ref) async => offers),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: CheckoutCouponList(
              codeField: controller,
              onApply: onApply ?? (_) {},
              onRemove: onRemove ?? () {},
            ),
          ),
        ),
      ),
    );
  }

  const flat = Coupon(
    id: 1,
    couponCode: 'SAVE100',
    discountType: DiscountType.flat,
    discountValue: 100,
    minimumOrderAmount: 299,
  );

  final percentage = Coupon(
    id: 2,
    couponCode: 'FIRST10',
    discountType: DiscountType.percentage,
    discountValue: 10,
    maxDiscountAmount: 200,
    expiryDate: DateTime(2026, 12, 31),
  );

  testWidgets('lists every running offer with its code', (tester) async {
    final controller = TextEditingController();
    addTearDown(controller.dispose);

    await tester.pumpWidget(host([flat, percentage], controller));
    await tester.pumpAndSettle();

    expect(find.text('Use code SAVE100'), findsOneWidget);
    expect(find.text('Use code FIRST10'), findsOneWidget);
  });

  testWidgets('renders conditions from the coupon\'s own fields',
      (tester) async {
    final controller = TextEditingController();
    addTearDown(controller.dispose);

    await tester.pumpWidget(host([flat, percentage], controller));
    await tester.pumpAndSettle();

    expect(find.text('Minimum order value ₹299'), findsOneWidget);
    expect(find.text('Maximum discount ₹200 per order'), findsOneWidget);
    expect(find.text('Valid till 31 Dec 2026'), findsOneWidget);
    // A percentage coupon's cap belongs in the headline too, because that is
    // the number that decides whether the offer is worth using.
    expect(find.text('10% OFF up to ₹200'), findsOneWidget);
    expect(find.text('₹100 OFF'), findsOneWidget);
  });

  testWidgets('Apply hands the code back rather than pricing it here',
      (tester) async {
    final controller = TextEditingController();
    addTearDown(controller.dispose);
    final applied = <String>[];

    await tester.pumpWidget(
        host([flat], controller, onApply: applied.add));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Apply'));
    await tester.pump();

    // No local discount arithmetic - the server prices the order.
    expect(applied, ['SAVE100']);
  });

  testWidgets('the tile for the code in the field reads Applied',
      (tester) async {
    final controller = TextEditingController(text: 'SAVE100');
    addTearDown(controller.dispose);

    await tester.pumpWidget(host([flat, percentage], controller));
    await tester.pumpAndSettle();

    expect(find.text('Applied'), findsOneWidget);
    expect(find.text('Apply'), findsOneWidget); // the other offer
  });

  testWidgets('typing a code by hand updates the tile, not just tapping Apply',
      (tester) async {
    // The regression this pins: the list used to take a plain String captured
    // at build time, and a TextField edit does not rebuild the checkout
    // screen - so a hand-typed code left its own tile still saying "Apply".
    final controller = TextEditingController();
    addTearDown(controller.dispose);

    await tester.pumpWidget(host([flat], controller));
    await tester.pumpAndSettle();
    expect(find.text('Apply'), findsOneWidget);

    controller.text = 'save100'; // lower case, as a person would type it
    await tester.pump();

    expect(find.text('Applied'), findsOneWidget);
    expect(find.text('Apply'), findsNothing);
  });

  testWidgets('a shop running no offers shows nothing at all', (tester) async {
    final controller = TextEditingController();
    addTearDown(controller.dispose);

    await tester.pumpWidget(host(const [], controller));
    await tester.pumpAndSettle();

    // Not an empty-state card: "no coupons available" makes checkout feel
    // poorer and the shopper lost nothing by never seeing it.
    expect(find.text('Available offers'), findsNothing);
  });

  testWidgets('a failed offers load never blocks checkout', (tester) async {
    final controller = TextEditingController();
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          activeOffersProvider
              .overrideWith((ref) async => throw Exception('offline')),
        ],
        child: MaterialApp(
          home: Scaffold(
            body: CheckoutCouponList(
              codeField: controller,
              onApply: (_) {},
              onRemove: () {},
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Available offers'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
