import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/platform_models.dart';
import 'package:gpstore/features/admin/presentation/platform_merchant_detail_screen.dart';
import 'package:gpstore/features/admin/presentation/platform_providers.dart';

/// One business, and the shops trading under it.
///
/// WHAT THESE PIN. The console lists merchants and shops in two tabs as if
/// they were parallel things, and they are not: a merchant owns one shop or
/// six, and stopping the merchant stops all of them at once. The tests that
/// matter here are the ones about that relationship - that every shop under a
/// business is shown, that the cascade is stated BEFORE the button is tapped
/// rather than discovered afterwards, and that a business which cannot hold a
/// shop is not offered the button to add one.
void main() {
  const oneShopBusiness = MerchantView(
    id: 6,
    merchantRef: 'M-000006',
    legalName: 'Gupta Hardware',
    displayName: 'Gupta Hardware',
    status: 'ACTIVE',
    ownerCustomerId: 993,
  );

  PlatformShopView shop(int id, String name, String status) => PlatformShopView(
        id: id,
        shopRef: 'S-${id.toString().padLeft(6, '0')}',
        merchantId: 6,
        merchantRef: 'M-000006',
        code: name.toUpperCase().replaceAll(' ', '-'),
        displayName: name,
        status: status,
      );

  Widget host(PlatformMerchantDetail detail) => ProviderScope(
        overrides: [
          platformMerchantDetailProvider(detail.merchant.id)
              .overrideWith((ref) async => detail),
        ],
        child: MaterialApp(
          home: PlatformMerchantDetailScreen(merchantId: detail.merchant.id),
        ),
      );

  testWidgets('every shop under the business is on the screen, and counted',
      (tester) async {
    await tester.pumpWidget(host(PlatformMerchantDetail(
      merchant: oneShopBusiness,
      shops: [
        shop(11, 'Gupta Hardware Mandi', 'ACTIVE'),
        shop(12, 'Gupta Hardware Station Road', 'PAUSED'),
        shop(13, 'Gupta Hardware Bypass', 'DRAFT'),
      ],
      shopCount: 3,
    )));
    await tester.pumpAndSettle();

    // Named as a heading AND as a fact on the business, because the two
    // answer different questions: "how big is this business" and "have I
    // scrolled past any".
    expect(find.text('3 shops'), findsOneWidget);

    // SCROLLED TO, not assumed on screen. Three shop cards do not fit a
    // 800x600 test surface - or a phone - and §45 puts no cap on how many
    // shops one merchant may have, so being in the list is the claim being
    // tested rather than being above the fold.
    for (final name in const [
      'Gupta Hardware Mandi',
      'Gupta Hardware Station Road',
      'Gupta Hardware Bypass',
    ]) {
      await tester.scrollUntilVisible(find.text(name), 200,
          scrollable: find.byType(Scrollable).first);
      expect(find.text(name), findsOneWidget);
    }
  });

  testWidgets('the cascade is stated before the button, not after it',
      (tester) async {
    await tester.pumpWidget(host(PlatformMerchantDetail(
      merchant: oneShopBusiness,
      shops: [
        shop(11, 'Gupta Hardware Mandi', 'ACTIVE'),
        shop(12, 'Gupta Hardware Station Road', 'ACTIVE'),
      ],
      shopCount: 2,
    )));
    await tester.pumpAndSettle();

    // THE POINT OF THE SCREEN. Suspending the business closes both shops, and
    // "it also closed the other one" is not something to learn from a
    // customer at a shutter.
    expect(
      find.textContaining('stops all 2 of its shops'),
      findsOneWidget,
      reason: 'a merchant-level suspension cascades to every shop, and a '
          'platform owner about to tap Suspend has to be told that first',
    );
    // "Pause trading" is the merchant's own label - a shop's is plain
    // "Pause" - so finding exactly one proves these are the BUSINESS's
    // controls and not a shop card's borrowed into the assertion.
    expect(find.widgetWithText(OutlinedButton, 'Pause trading'), findsOneWidget);
  });

  testWidgets('a single-shop business is not warned about shops it does not have',
      (tester) async {
    await tester.pumpWidget(host(PlatformMerchantDetail(
      merchant: oneShopBusiness,
      shops: [shop(11, 'Gupta Hardware Mandi', 'ACTIVE')],
      shopCount: 1,
    )));
    await tester.pumpAndSettle();

    expect(find.textContaining('stops all'), findsNothing);
    expect(find.text('1 shop'), findsOneWidget,
        reason: '"1 shops" is the kind of detail that makes a console look '
            'like nobody uses it');
  });

  testWidgets('a business nobody has approved yet is offered no Add shop',
      (tester) async {
    await tester.pumpWidget(host(const PlatformMerchantDetail(
      merchant: MerchantView(
          id: 7, legalName: 'Just Applied', status: 'APPLICATION'),
      shops: [],
      shopCount: 0,
    )));
    await tester.pumpAndSettle();

    // The server refuses a shop under a merchant that is not APPROVED or
    // ACTIVE. A button that is present and ends in that refusal explains
    // nothing about what to do first.
    expect(find.widgetWithText(FloatingActionButton, 'Add shop'), findsNothing);
    expect(find.widgetWithText(OutlinedButton, 'Send for review'), findsOneWidget,
        reason: 'which IS the next step, and the only one the server allows');
    expect(find.widgetWithText(OutlinedButton, 'Approve'), findsNothing,
        reason: 'APPLICATION cannot go straight to APPROVED');
  });

  testWidgets('an approved business with no shops says so and offers Add shop',
      (tester) async {
    await tester.pumpWidget(host(const PlatformMerchantDetail(
      merchant: MerchantView(
          id: 8, legalName: 'Papers In Order', status: 'APPROVED'),
      shops: [],
      shopCount: 0,
    )));
    await tester.pumpAndSettle();

    expect(find.textContaining('No shops under this business yet'),
        findsOneWidget);
    expect(find.widgetWithText(FloatingActionButton, 'Add shop'), findsOneWidget);
  });

  testWidgets('the owner can be got back in from here, not only from the list',
      (tester) async {
    await tester.pumpWidget(host(PlatformMerchantDetail(
      merchant: oneShopBusiness,
      shops: [shop(11, 'Gupta Hardware Mandi', 'ACTIVE')],
      shopCount: 1,
    )));
    await tester.pumpAndSettle();

    // A merchant on the phone saying "it will not let me in" is the moment
    // the platform owner is least able to remember which screen holds the
    // recovery. It is on both.
    expect(find.text('Reset password'), findsOneWidget);
    expect(find.text('New activation code'), findsOneWidget,
        reason: 'the code and the password are lost separately, so reissuing '
            'one must not force the other');
  });

  testWidgets('a business with no owner account is offered no recovery',
      (tester) async {
    await tester.pumpWidget(host(const PlatformMerchantDetail(
      merchant: MerchantView(
          id: 10, legalName: 'Paperwork Only', status: 'APPROVED'),
      shops: [],
      shopCount: 0,
    )));
    await tester.pumpAndSettle();

    // Registering a business without an owner login is allowed - the papers
    // sometimes arrive before the person does - and there is no account to
    // reset until one is opened.
    expect(find.text('Reset password'), findsNothing);
    expect(find.text('New activation code'), findsNothing);
  });

  testWidgets('a removed business offers nothing and says its records stay',
      (tester) async {
    await tester.pumpWidget(host(const PlatformMerchantDetail(
      merchant:
          MerchantView(id: 9, legalName: 'Closed Down', status: 'REMOVED'),
      shops: [],
      shopCount: 0,
    )));
    await tester.pumpAndSettle();

    expect(find.text('This business is closed. Its records stay.'),
        findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, 'Remove'), findsNothing);
    expect(find.widgetWithText(FloatingActionButton, 'Add shop'), findsNothing);
  });
}
