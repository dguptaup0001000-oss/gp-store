import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/platform_repository.dart';
import 'package:gpstore/features/admin/domain/platform_models.dart';
import 'package:gpstore/features/admin/presentation/platform_onboarding_forms.dart';
import 'package:gpstore/features/admin/presentation/platform_providers.dart';

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
  _onboardHandsOverBothHalves();
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

  group('onboarding a merchant in one screen', () {
    // THE ONE FIELD THAT IS NOT A NAME. Everything else on the form is typed
    // straight out of a conversation; this one is pasted, and a pin that is
    // wrong in a way nobody notices puts a real shop somewhere it will never
    // be found by the distance search that decides who is offered it.
    group('the pin', () {
      ({double lat, double lng})? parse(String raw) =>
          PlatformOnboardMerchantDialog.parseLocation(raw);

      test('takes exactly what Google Maps copies', () {
        final where = parse('26.7606, 83.3732');
        expect(where!.lat, 26.7606);
        expect(where.lng, 83.3732);
      });

      test('a space instead of a comma still works', () {
        // Both are what actually lands on a clipboard, and refusing one of
        // them would look like the field was broken.
        expect(parse('26.7606 83.3732')!.lng, 83.3732);
        expect(parse('  26.7606,83.3732  ')!.lat, 26.7606);
      });

      test('negatives survive, because half the world is one', () {
        final where = parse('-33.8688, 151.2093');
        expect(where!.lat, -33.8688);
        expect(where.lng, 151.2093);
      });

      test('one number is not a place', () {
        expect(parse('26.7606'), isNull);
        expect(parse(''), isNull);
        expect(parse('26.7606, 83.3732, 40'), isNull);
      });

      test('words are not coordinates', () {
        expect(parse('near the bus stand'), isNull);
        expect(parse('26.7606, north'), isNull);
      });

      test('a pair that is not a place on Earth is refused', () {
        expect(parse('91, 20'), isNull, reason: 'no latitude past 90');
        expect(parse('-90.1, 20'), isNull);
        expect(parse('20, 181'), isNull, reason: 'no longitude past 180');
        expect(parse('20, -180.5'), isNull);
      });

      test('the poles and the meridian are inside the range, not outside', () {
        // An exclusive bound would refuse 90/180 outright. They are real
        // coordinates, and a parser that rejected its own limits would be a
        // bug nobody hits until somebody does.
        expect(parse('90, 180'), isNotNull);
        expect(parse('-90, -180'), isNotNull);
        expect(parse('0, 0'), isNotNull);
      });

      test('the halves swapped are NOT caught here, and cannot be', () {
        // 83.3732, 26.7606 is the mistake somebody actually makes, and it
        // parses: it is a real pin, off the north coast of Greenland. Nothing
        // in a range check can tell it from a deliberate one, so this is
        // recorded rather than asserted away - the form's job is to make the
        // swap unlikely by taking ONE pasted string instead of two fields
        // somebody fills in by hand, not to detect it afterwards.
        final swapped = parse('83.3732, 26.7606');
        expect(swapped, isNotNull);
        expect(swapped!.lat, 83.3732);
      });
    });

    testWidgets('it says what the owner is actually getting', (tester) async {
      await tester.pumpWidget(host(const PlatformOnboardMerchantDialog()));
      await tester.pumpAndSettle();

      expect(find.textContaining('one-time password'), findsOneWidget);
      // AND WHAT THEY ARE NOT GETTING. The shop opens with empty shelves, so
      // an owner who expects a trading storefront and checks for it would
      // find nothing and assume this failed.
      expect(find.textContaining('not trading yet'), findsOneWidget,
          reason: 'the shop cannot sell until the merchant puts stock up, and '
              'the form has to say so before it is submitted');
      expect(find.textContaining('Let them trade'), findsOneWidget);
    });

    testWidgets('nothing is sent without a business name', (tester) async {
      await tester.pumpWidget(host(const PlatformOnboardMerchantDialog()));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Onboard'));
      await tester.pumpAndSettle();

      expect(find.text('The business needs a name'), findsOneWidget);
    });

    testWidgets('a phone number typed into the email field is caught here',
        (tester) async {
      await tester.pumpWidget(host(const PlatformOnboardMerchantDialog()));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Business name *'), 'Sharma Kirana');
      await tester.enterText(
          find.widgetWithText(TextFormField, "Owner's name *"), 'Ravi Sharma');
      await tester.enterText(
          find.widgetWithText(TextFormField, "Owner's email *"), '9876543210');
      await tester.tap(find.text('Onboard'));
      await tester.pumpAndSettle();

      expect(find.text('That is not an email address'), findsOneWidget);
    });

    testWidgets('an unparseable pin stops the form rather than the server',
        (tester) async {
      await tester.pumpWidget(host(const PlatformOnboardMerchantDialog()));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Business name *'), 'Sharma Kirana');
      await tester.enterText(
          find.widgetWithText(TextFormField, "Owner's name *"), 'Ravi Sharma');
      await tester.enterText(find.widgetWithText(TextFormField, "Owner's email *"),
          'ravi@sharmakirana.test');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Shop location *'), 'near the mandi');
      await tester.tap(find.text('Onboard'));
      await tester.pumpAndSettle();

      // _save() reads parseLocation(...)! - a form that submitted with an
      // unparseable pin would throw on the bang rather than say anything.
      expect(find.text('Paste a latitude and longitude, like 26.7606, 83.3732'),
          findsOneWidget);
    });

    testWidgets('a delivery radius of zero is refused', (tester) async {
      await tester.pumpWidget(host(const PlatformOnboardMerchantDialog()));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Business name *'), 'Sharma Kirana');
      await tester.enterText(
          find.widgetWithText(TextFormField, "Owner's name *"), 'Ravi Sharma');
      await tester.enterText(find.widgetWithText(TextFormField, "Owner's email *"),
          'ravi@sharmakirana.test');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Shop location *'), '26.7606, 83.3732');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Delivers up to (km) *'), '0');
      await tester.tap(find.text('Onboard'));
      await tester.pumpAndSettle();

      // A radius of zero is a shop nobody is ever near enough to order from,
      // which is indistinguishable from the shop being broken.
      expect(find.text('Must be more than zero'), findsOneWidget);
    });

    testWidgets('the radius starts at a number rather than empty',
        (tester) async {
      await tester.pumpWidget(host(const PlatformOnboardMerchantDialog()));
      await tester.pumpAndSettle();

      // Six fields is the whole point of this screen; one of them having a
      // sensible default makes it five.
      expect(find.text('5'), findsOneWidget);
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

    testWidgets('both halves of the first login are shown together',
        (tester) async {
      await showIt(tester, const OpenedStaffAccount(
        customerId: 55,
        email: 'ravi@sharmakirana.test',
        role: 'ADMIN',
        oneTimePassword: 'k7Rmq3xTbYw9Zc',
        activationCode: '7KQ4N8ZP2H5RX9M',
      ));

      // USELESS APART. A merchant sent only the password cannot sign in at
      // all, so putting the two on separate screens - or behind two copies -
      // is a way to hand over half a credential and not notice.
      expect(find.text('k7Rmq3xTbYw9Zc'), findsOneWidget);
      expect(find.text('7KQ4N8ZP2H5RX9M'), findsOneWidget);
      expect(find.text('Copy all'), findsOneWidget);
    });

    testWidgets('it says the code is for the first sign-in only',
        (tester) async {
      await showIt(tester, const OpenedStaffAccount(
        customerId: 55,
        email: 'ravi@sharmakirana.test',
        oneTimePassword: 'k7Rmq3xTbYw9Zc',
        activationCode: '7KQ4N8ZP2H5RX9M',
      ));

      // §28. Without this sentence a merchant files the code away as a
      // permanent third password and keeps it written down beside the phone,
      // which is the opposite of what a second factor is for.
      expect(find.textContaining('FIRST sign-in'), findsOneWidget);
    });

    testWidgets('a reissue shows the code alone, with no empty password field',
        (tester) async {
      // A reissue mints a code and no password. A blank "One-time password"
      // row would read as a password that is somehow empty.
      await showIt(tester, const OpenedStaffAccount(
        customerId: 55,
        email: 'ravi@sharmakirana.test',
        activationCode: '7KQ4N8ZP2H5RX9M',
      ));

      expect(find.text('7KQ4N8ZP2H5RX9M'), findsOneWidget);
      expect(find.text('One-time password'), findsNothing);
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

/// Onboarding a merchant has to hand over BOTH halves of the first sign-in.
///
/// WHAT WENT WRONG, AND WHY THIS TEST EXISTS. `POST /api/platform/onboard`
/// mints a one-time password AND a 15-character activation code, and the
/// server's first-login check demands both. The Flutter model for that
/// response carried only the password, so the console showed the platform
/// owner one of the two secrets and silently dropped the other. Every merchant
/// onboarded through that dialog would have been handed credentials that
/// cannot sign in - and the only recovery (issue a new code) is on a screen
/// they were about to be told they could not reach.
///
/// The gap was in the plumbing between the response and the dialog, so the
/// test drives exactly that: a fake repository returning both, and an
/// assertion that both reach the screen.
class _RepoThatReturnsBothHalves implements PlatformRepository {
  @override
  Future<OnboardedMerchant> onboardMerchant({
    required String businessName,
    required String ownerName,
    required String ownerEmail,
    String? ownerPhone,
    String? shopCode,
    required double latitude,
    required double longitude,
    required double maxDeliveryRadiusKm,
    String? timeZone,
  }) async {
    return const OnboardedMerchant(
      merchantId: 6,
      businessName: 'Gupta Hardware',
      shopId: 11,
      shopCode: 'GUPTA-HARDWARE',
      ownerCustomerId: 993,
      ownerEmail: 'owner@example.test',
      oneTimePassword: 'Temp-Pass-8834',
      activationCode: 'Kx7mRt2QpLn4Vb9',
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName} is not part of this test');
}

void _onboardHandsOverBothHalves() {
  testWidgets('onboarding shows the activation code beside the password',
      (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        platformRepositoryProvider
            .overrideWithValue(_RepoThatReturnsBothHalves()),
      ],
      child: const MaterialApp(
        home: Scaffold(body: PlatformOnboardMerchantDialog()),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(
        find.widgetWithText(TextFormField, 'Business name *'), 'Gupta Hardware');
    await tester.enterText(
        find.widgetWithText(TextFormField, "Owner's name *"), 'Deepak Gupta');
    await tester.enterText(
        find.widgetWithText(TextFormField, "Owner's email *"),
        'owner@example.test');
    await tester.enterText(
        find.widgetWithText(TextFormField, 'Shop location *'), '26.7606, 83.3732');

    await tester.tap(find.widgetWithText(FilledButton, 'Onboard'));
    // pump, NOT pumpAndSettle. The Onboard button is left spinning underneath
    // the credentials dialog on purpose - the form is not coming back - and a
    // CircularProgressIndicator never settles.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.text('Temp-Pass-8834'), findsOneWidget);
    expect(
      find.text('Kx7mRt2QpLn4Vb9'),
      findsOneWidget,
      reason: 'the server minted an activation code and the first sign-in '
          'demands it; a dialog that shows only the password hands over '
          'credentials that cannot get in',
    );
    expect(find.textContaining('FIRST sign-in'), findsOneWidget);
    // One copy, both halves - they are useless apart, and a second
    // copy-paste is a second chance to send the wrong thing.
    expect(find.text('Copy all'), findsOneWidget);
  });
}
