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
    /// Turns the owner's login off, to reach the escape hatch behind it.
    ///
    /// ensureVisible first: the dialog scrolls, and by the time the login
    /// fields are on screen the checkbox itself can be below the fold.
    Future<void> untickTheLogin(WidgetTester tester) async {
      final box = find.byType(CheckboxListTile).first;
      await tester.ensureVisible(box);
      await tester.pumpAndSettle();
      await tester.tap(box);
      await tester.pumpAndSettle();
    }

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

    testWidgets("the owner's login is offered by default, not opt-in",
        (tester) async {
      await tester.pumpWidget(host(const PlatformMerchantFormDialog()));
      await tester.pumpAndSettle();

      // THE DEFAULT IS THE FEATURE. Every merchant needs somebody who can
      // sign in, and until this existed the only way to create that account
      // was SQL on the box. Making it opt-in would leave the common case
      // exactly where it was.
      expect(find.text("Owner's name *"), findsOneWidget);
      expect(find.text("Owner's email *"), findsOneWidget);
      // And the escape hatch is out of the way while it is on.
      expect(find.text('Existing owner account id'), findsNothing);
    });

    testWidgets('it says the password is handed over once and then lost',
        (tester) async {
      await tester.pumpWidget(host(const PlatformMerchantFormDialog()));
      await tester.pumpAndSettle();

      // The owner has to know BEFORE submitting that this is their only
      // chance to copy it, and that their own copy stops working afterwards -
      // that second half is what makes the merchant's actions their own.
      expect(find.textContaining('one-time password'), findsOneWidget);
      expect(find.textContaining('no longer have it'), findsOneWidget);
    });

    testWidgets('a login will not be opened without an email to log in with',
        (tester) async {
      await tester.pumpWidget(host(const PlatformMerchantFormDialog()));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Legal name *'), 'Sharma Kirana');
      await tester.enterText(
          find.widgetWithText(TextFormField, "Owner's name *"), 'Ravi Sharma');
      await tester.tap(find.text('Register'));
      await tester.pumpAndSettle();

      expect(find.text('An email is the login, so it is required'),
          findsOneWidget);
    });

    testWidgets('something that is not an address is caught before it is sent',
        (tester) async {
      await tester.pumpWidget(host(const PlatformMerchantFormDialog()));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Legal name *'), 'Sharma Kirana');
      await tester.enterText(
          find.widgetWithText(TextFormField, "Owner's name *"), 'Ravi Sharma');
      await tester.enterText(
          find.widgetWithText(TextFormField, "Owner's email *"), '9876543210');
      await tester.tap(find.text('Register'));
      await tester.pumpAndSettle();

      // A phone number in the email field is the mistake somebody actually
      // makes, and it would come back as an opaque server refusal.
      expect(find.text('That is not an email address'), findsOneWidget);
    });

    testWidgets('unticking it warns that the shop has nobody who can sign in',
        (tester) async {
      await tester.pumpWidget(host(const PlatformMerchantFormDialog()));
      await tester.pumpAndSettle();

      await untickTheLogin(tester);

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

      // The escape hatch, for a merchant who already has an admin account.
      await untickTheLogin(tester);

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Legal name *'), 'Sharma Kirana');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Existing owner account id'),
          'sharma@x.com');
      await tester.tap(find.text('Register'));
      await tester.pumpAndSettle();

      expect(find.text('An account id is a number'), findsOneWidget);
    });
  });

  group('handing the password over', () {
    /// The dialog the console shows once, and never again.
    Future<void> showIt(WidgetTester tester, OpenedStaffAccount account) async {
      await tester.pumpWidget(host(Builder(
        builder: (context) => TextButton(
          onPressed: () => showOneTimePassword(context, account),
          child: const Text('open'),
        ),
      )));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
    }

    const opened = OpenedStaffAccount(
      customerId: 55,
      email: 'ravi@sharmakirana.test',
      role: 'ADMIN',
      oneTimePassword: 'k7Rmq3xTbYw9Zc',
    );

    testWidgets('both halves of the credential are on screen', (tester) async {
      await showIt(tester, opened);

      // An owner reading this out over the phone needs the login as well as
      // the password; the email they typed two screens ago is not in front
      // of them any more.
      expect(find.text('ravi@sharmakirana.test'), findsOneWidget);
      expect(find.text('k7Rmq3xTbYw9Zc'), findsOneWidget);
    });

    testWidgets('it says, in the button, that dismissing loses it',
        (tester) async {
      await showIt(tester, opened);

      // NOT "OK". Dismissing this destroys the only copy - there is no route
      // that returns it - so the button has to say what it does.
      expect(find.text("I've saved it"), findsOneWidget);
      expect(find.textContaining('cannot be looked up again'), findsOneWidget);
    });

    testWidgets('a stray tap outside cannot dismiss it', (tester) async {
      await showIt(tester, opened);

      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();

      expect(find.text('k7Rmq3xTbYw9Zc'), findsOneWidget,
          reason: 'barrierDismissible must stay false: a mis-tap here loses '
              'the password permanently and the merchant cannot be onboarded '
              'without a reset');
    });

    testWidgets('a reset with no password in the reply offers nothing to copy',
        (tester) async {
      // Defensive, not hypothetical-only: the model makes oneTimePassword
      // nullable because an older server, or a partial reply, can omit it.
      // A Copy button that copied the empty string would look like it worked.
      await showIt(tester,
          const OpenedStaffAccount(customerId: 55, email: 'ravi@x.test'));

      expect(find.text('Copy password'), findsNothing);
      expect(find.text("I've saved it"), findsOneWidget);
    });
  });
}
