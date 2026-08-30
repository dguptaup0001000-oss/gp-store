import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/theme/app_theme.dart';
import 'package:gpstore/core/util/app_haptics.dart';
import 'package:gpstore/features/address/domain/address_models.dart';
import 'package:gpstore/features/address/presentation/address_providers.dart';
import 'package:gpstore/features/checkout/data/checkout_repository.dart';
import 'package:gpstore/features/checkout/domain/checkout_models.dart';
import 'package:gpstore/features/checkout/presentation/checkout_providers.dart';
import 'package:gpstore/features/checkout/presentation/checkout_screen.dart';
import 'package:gpstore/features/support/domain/store_info_model.dart';
import 'package:gpstore/features/support/presentation/support_providers.dart';

import '../../support/test_api_client.dart';

class _FakeCheckoutRepository extends CheckoutRepository {
  _FakeCheckoutRepository()
      : super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  String? lastCoupon;

  @override
  Future<CheckoutPreview> getPreview({
    required int addressId,
    String? couponCode,
  }) async {
    lastCoupon = couponCode;
    if (couponCode == 'SAVE50') {
      return const CheckoutPreview(
        subtotal: 390,
        discountAmount: 50,
        deliveryFee: 5,
        estimatedTotal: 345,
        freeDeliveryApplied: false,
        deliverable: true,
        estimatedDeliveryMinutes: 10,
      );
    }
    return const CheckoutPreview(
      subtotal: 390,
      discountAmount: 0,
      deliveryFee: 5,
      estimatedTotal: 395,
      freeDeliveryApplied: false,
      deliverable: true,
      estimatedDeliveryMinutes: 10,
    );
  }
}

const _address = AddressModel(
  id: 1,
  fullName: 'deepak kumar gupta',
  mobileNumber: '9999999999',
  houseNo: 'ward no.11',
  area: 'paniyahawa',
  city: 'paniyahawa',
  state: 'UP',
  pincode: '274304',
  latitude: 26.74,
  longitude: 83.89,
);

void main() {
  setUpAll(setUpFakeSecureStorage);
  setUp(() {
    AppHaptics.resetForTest();
    AppHaptics.enabled = false;
  });

  testWidgets('coupon field and Apply lay out under the app theme',
      (tester) async {
    final controller = TextEditingController();
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.light,
        home: Scaffold(
          body: CheckoutCouponRow(
            controller: controller,
            onApply: () {},
          ),
        ),
      ),
    );

    expect(tester.takeException(), isNull);
    expect(find.byType(TextField), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Apply'), findsOneWidget);

    final fieldSize = tester.getSize(find.byType(TextField));
    final buttonSize = tester.getSize(find.widgetWithText(FilledButton, 'Apply'));
    expect(fieldSize.width, greaterThan(80));
    expect(fieldSize.height, greaterThan(0));
    expect(buttonSize.width, greaterThan(0));
    expect(buttonSize.height, greaterThan(0));
  });

  testWidgets('Apply sends the typed code to onApply', (tester) async {
    final controller = TextEditingController();
    addTearDown(controller.dispose);
    var applied = false;

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.light,
        home: Scaffold(
          body: CheckoutCouponRow(
            controller: controller,
            onApply: () => applied = true,
          ),
        ),
      ),
    );

    await tester.enterText(find.byType(TextField), 'FREEDEL10');
    await tester.tap(find.widgetWithText(FilledButton, 'Apply'));
    expect(controller.text, 'FREEDEL10');
    expect(applied, isTrue);
  });

  testWidgets('checkout shows the coupon field and Apply updates the total',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final fake = _FakeCheckoutRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          myAddressesProvider.overrideWith((ref) async => [_address]),
          storeInfoProvider.overrideWith(
            (ref) async => const StoreInfo(
              supportPhone: '',
              supportWhatsapp: '',
              supportEmail: '',
            ),
          ),
          checkoutRepositoryProvider.overrideWithValue(fake),
        ],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const CheckoutScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Coupon Code'), findsOneWidget);
    expect(find.byType(CheckoutCouponRow), findsOneWidget);
    expect(find.text('Enter coupon code (optional)'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Apply'), findsOneWidget);
    expect(find.text('₹395'), findsWidgets);

    await tester.enterText(find.byType(TextField), 'SAVE50');
    await tester.tap(find.widgetWithText(FilledButton, 'Apply'));
    await tester.pumpAndSettle();

    expect(fake.lastCoupon, 'SAVE50');
    expect(find.text('Discount'), findsOneWidget);
    expect(find.text('-₹50'), findsOneWidget);
    expect(find.text('₹345'), findsWidgets);
  });
}
