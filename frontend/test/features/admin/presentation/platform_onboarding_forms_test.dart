import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/platform_models.dart';
import 'package:gpstore/features/admin/presentation/platform_onboarding_forms.dart';

/// Opening a shop, from the console.
///
/// WHAT THESE PIN, and it is not the widget tree. The server refuses a shop
/// under a merchant that is not APPROVED or ACTIVE, and it is right to - but a
/// form that let somebody fill in seven fields and then relayed a conflict
/// would be a worse answer than one that says which merchants are ready
/// before they start. The eligibility rule is therefore duplicated here
/// deliberately, and these tests are what stop the copy drifting into
/// something more permissive than the server.
void main() {
  Widget host(Widget child) => ProviderScope(
        child: MaterialApp(home: Scaffold(body: child)),
      );

  const approved = MerchantView(id: 1, legalName: 'Ready Retail', status: 'APPROVED');
  const trading = MerchantView(id: 2, legalName: 'Trading Retail', status: 'ACTIVE');
  const applied = MerchantView(id: 3, legalName: 'Just Applied', status: 'APPLICATION');
  const suspended = MerchantView(id: 4, legalName: 'Suspended Co', status: 'SUSPENDED');

  group('opening a shop', () {
    testWidgets('a merchant nobody has approved yet is not offered', (tester) async {
      await tester.pumpWidget(host(const PlatformShopFormDialog(
        merchants: [approved, applied, suspended],
      )));
      await tester.pumpAndSettle();

      await tester.tap(find.byType(DropdownButtonFormField<int>));
      await tester.pumpAndSettle();

      expect(find.text('Ready Retail  ·  APPROVED'), findsWidgets);
      expect(find.textContaining('Just Applied'), findsNothing,
          reason: 'a merchant in APPLICATION cannot hold a shop, and offering '
              'it means filling in the whole form to be told so');
      expect(find.textContaining('Suspended Co'), findsNothing,
          reason: 'a suspended merchant must not be handed a new storefront');
    });

    testWidgets('ACTIVE counts as ready, not only APPROVED', (tester) async {
      await tester.pumpWidget(host(const PlatformShopFormDialog(
        merchants: [trading, applied],
      )));
      await tester.pumpAndSettle();

      await tester.tap(find.byType(DropdownButtonFormField<int>));
      await tester.pumpAndSettle();

      // A merchant already trading opening their SECOND shop is the ordinary
      // case, not a corner of one - it is how one business runs three kiranas.
      expect(find.text('Trading Retail  ·  ACTIVE'), findsWidgets);
    });

    testWidgets('with nobody eligible it explains the next step and offers no submit',
        (tester) async {
      await tester.pumpWidget(host(const PlatformShopFormDialog(
        merchants: [applied],
      )));
      await tester.pumpAndSettle();

      expect(find.textContaining('No merchant is ready to hold a shop yet'),
          findsOneWidget);
      expect(find.textContaining('approve one on the Merchants tab first',
              findRichText: true),
          findsOneWidget);
      // The commonest way to reach this screen is having just registered a
      // merchant. A live "Open as draft" button here would take seven fields
      // and then fail.
      expect(find.text('Open as draft'), findsNothing);
      expect(find.text('Close'), findsOneWidget);
      expect(find.byType(DropdownButtonFormField<int>), findsNothing);
    });

    testWidgets('the only eligible merchant is chosen for you', (tester) async {
      await tester.pumpWidget(host(const PlatformShopFormDialog(
        merchants: [approved, applied],
      )));
      await tester.pumpAndSettle();

      // One candidate and an empty dropdown is a required field somebody has
      // to fill in to say the only thing it could say.
      expect(find.text('Ready Retail  ·  APPROVED'), findsOneWidget);
    });

    testWidgets('a shop will not be opened without a code', (tester) async {
      await tester.pumpWidget(host(const PlatformShopFormDialog(
        merchants: [approved],
      )));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Open as draft'));
      await tester.pumpAndSettle();

      expect(find.text('A shop needs a code'), findsOneWidget);
    });

    testWidgets('a latitude that is not a number is caught before it is sent',
        (tester) async {
      await tester.pumpWidget(host(const PlatformShopFormDialog(
        merchants: [approved],
      )));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Shop code *'), 'SHARMA-1');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Latitude'), 'north a bit');
      await tester.tap(find.text('Open as draft'));
      await tester.pumpAndSettle();

      expect(find.text('A latitude is a number'), findsOneWidget);
    });
  });

  group('registering a merchant', () {
    testWidgets('says the business cannot trade or hold a shop yet',
        (tester) async {
      await tester.pumpWidget(host(const PlatformMerchantFormDialog()));
      await tester.pumpAndSettle();

      expect(find.textContaining('lands as an APPLICATION'), findsOneWidget,
          reason: 'the owner wanted a shop and is getting a business in '
              'review - the form has to say so or the refusal later is a '
              'surprise');
    });

    testWidgets('a merchant will not be registered without a legal name',
        (tester) async {
      await tester.pumpWidget(host(const PlatformMerchantFormDialog()));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Register'));
      await tester.pumpAndSettle();

      expect(find.text('A merchant needs a legal name'), findsOneWidget);
    });

    testWidgets('warns that a shop with no owner account has nobody to sign in',
        (tester) async {
      await tester.pumpWidget(host(const PlatformMerchantFormDialog()));
      await tester.pumpAndSettle();

      // THE FIELD WHOSE ABSENCE IS INVISIBLE. Everything looks right until
      // somebody tries to open the shop's back office.
      expect(
          find.textContaining('nobody who can sign in'), findsOneWidget,
          reason: 'leaving the owner out produces shops no one can administer, '
              'and nothing about the shop looks wrong until then');
    });

    testWidgets('an owner id that is not a number is caught before it is sent',
        (tester) async {
      await tester.pumpWidget(host(const PlatformMerchantFormDialog()));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Legal name *'), 'Sharma Kirana');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Owner account id'), 'sharma@x.com');
      await tester.tap(find.text('Register'));
      await tester.pumpAndSettle();

      expect(find.text('An account id is a number'), findsOneWidget);
    });
  });
}
