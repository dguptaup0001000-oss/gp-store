import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/api/error_messages.dart';
import 'package:gpstore/features/profile/domain/profile_models.dart';
import 'package:gpstore/features/profile/presentation/change_password_screen.dart';
import 'package:gpstore/features/profile/presentation/profile_providers.dart';
import 'package:gpstore/shared/widgets/signed_in_home.dart';

import '../../support/test_api_client.dart';

/// An account still on a one-time password gets the password screen INSTEAD
/// of the app, not on top of it.
///
/// The backend refuses /api/customers/me while the flag is set, so the
/// refusal itself is the signal - which is why this is handled where the
/// profile is loaded rather than in the login flow. One branch then covers
/// both cases that matter: a first sign-in on a freshly opened account, and a
/// password the platform reset while somebody was already signed in.
void main() {
  setUpAll(setUpFakeSecureStorage);

  Widget app(Object error) {
    return ProviderScope(
      overrides: [
        myProfileProvider.overrideWith((ref) => Future<Profile>.error(error)),
      ],
      child: MaterialApp(
        home: SignedInHome(
          builder: (profile) => const Scaffold(
            body: Center(child: Text('THE CONSOLE')),
          ),
        ),
      ),
    );
  }

  final owesAChange = ApiException(
    statusCode: 403,
    message: 'Set your own password before using the app. '
        'This account is still on the one-time password it was created with.',
    code: passwordChangeRequiredCode,
  );

  testWidgets('the password screen replaces the app entirely', (tester) async {
    await tester.pumpWidget(app(owesAChange));
    await tester.pumpAndSettle();

    expect(find.byType(ChangePasswordScreen), findsOneWidget);
    expect(find.text('Set your password'), findsOneWidget);
    // THE WHOLE POINT: not a banner over a working console. Every call the
    // console would make answers 403, so showing it would be a lie.
    expect(find.text('THE CONSOLE'), findsNothing);
  });

  testWidgets('it asks for the one-time password by that name', (tester) async {
    await tester.pumpWidget(app(owesAChange));
    await tester.pumpAndSettle();

    // "Current password" is meaningless to a merchant holding a slip of
    // paper from the platform owner. The field has to name the thing they
    // were actually given.
    expect(find.text('One-time password'), findsOneWidget);
    expect(find.text('Current password'), findsNothing);
  });

  testWidgets('there is no way back, and one way out', (tester) async {
    await tester.pumpWidget(app(owesAChange));
    await tester.pumpAndSettle();

    // No back button: there is nothing underneath, and a gate somebody can
    // navigate around is not a gate.
    expect(find.byType(BackButton), findsNothing);
    // But sign out must exist. Somebody holding the wrong account's slip
    // would otherwise be stuck on a screen they cannot satisfy.
    expect(find.text('Sign out'), findsOneWidget);
  });

  testWidgets('the system back gesture does not escape it', (tester) async {
    await tester.pumpWidget(app(owesAChange));
    await tester.pumpAndSettle();

    // Android's back gesture does not go through the AppBar, so hiding the
    // button is not enough - PopScope has to refuse as well.
    // Matched by predicate rather than byType: PopScope is generic, so its
    // runtime type depends on an inferred parameter that is not worth
    // pinning in a test about back navigation.
    final scopes = tester
        .widgetList<Widget>(find.byWidgetPredicate((w) => w is PopScope))
        .cast<PopScope>();
    expect(scopes, isNotEmpty, reason: 'the forced screen must wrap a PopScope');
    expect(scopes.every((scope) => scope.canPop == false), isTrue);
  });

  testWidgets('an ordinary 403 still shows the error, not the password screen',
      (tester) async {
    // THE REGRESSION THIS GUARDS. Shop-scope refusals, routes a role may not
    // reach, a suspended shop - all 403. If those were read as "you owe a
    // password change", the console would be replaced by a form asking a
    // merchant to change a password that is already their own, and the real
    // reason would never be shown.
    //
    // THE EXAMPLE USED TO BE THE ZERO-SHOP MESSAGE. It is now a cross-shop
    // refusal instead, because the zero-shop one is no longer passed through
    // verbatim - it is re-worded into an explanation, which is asserted in its
    // own case below. What this case is actually about is the 403 not being
    // mistaken for a password demand, and that is unchanged.
    await tester.pumpWidget(app(
        ApiException(
            statusCode: 403,
            message: 'This account is not associated with this shop.')));
    await tester.pumpAndSettle();

    expect(find.byType(ChangePasswordScreen), findsNothing);
    expect(find.text('This account is not associated with this shop.'),
        findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
  });

  testWidgets('a business with no shop is explained, and is not a password problem',
      (tester) async {
    // A REAL MERCHANT'S SCREEN. GUPT SAREE's owner signed in with a one-time
    // password against a business that had no shop, so BOTH readings were
    // available and both were wrong: it is not a password demand, and it is
    // not a refusal either.
    await tester.pumpWidget(app(
        ApiException(
            statusCode: 403,
            message: 'This account is not associated with a shop.')));
    await tester.pumpAndSettle();

    expect(find.byType(ChangePasswordScreen), findsNothing);
    expect(find.textContaining('No shop has been set up'), findsOneWidget);
    expect(find.text('This account is not associated with a shop.'), findsNothing,
        reason: 'the backend sentence reads as a refusal and this is not one');
    // Still not a dead end.
    expect(find.text('Retry'), findsOneWidget);
    expect(find.text('Sign out'), findsOneWidget);
  });

  group('the ordinary error screen is not a dead end', () {
    // WHAT WENT WRONG. A merchant on a build older than the forced-change
    // screen signed in holding a one-time password, landed on this screen,
    // and the only control was Retry - which re-asks a question with one
    // permanent answer. Signed in, so no login page to go back to; nothing
    // on screen to sign out with. Stuck.
    final ordinary = ApiException(
        statusCode: 403, message: 'This account is not associated with a shop.');

    testWidgets('it offers a way out, not just a way round again',
        (tester) async {
      await tester.pumpWidget(app(ordinary));
      await tester.pumpAndSettle();

      expect(find.text('Retry'), findsOneWidget);
      expect(find.text('Sign out'), findsOneWidget,
          reason: 'an error Retry cannot fix must still be escapable');
    });

    testWidgets('it names the build, because Profile is behind this failure',
        (tester) async {
      // "Is this actually the latest APK" is repeatedly the real answer, and
      // the Profile screen that normally shows the build sha is behind the
      // very call that just failed.
      await tester.pumpWidget(app(ordinary));
      await tester.pumpAndSettle();

      expect(find.textContaining('Build '), findsOneWidget);
    });
  });

  testWidgets('a signed-in account with nothing owed sees the app',
      (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        myProfileProvider.overrideWith((ref) async => const Profile(
              id: 7,
              fullName: 'Deepak',
              email: 'owner@kirana.test',
              role: 'ADMIN',
            )),
      ],
      child: MaterialApp(
        home: SignedInHome(
          builder: (profile) => const Scaffold(
            body: Center(child: Text('THE CONSOLE')),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('THE CONSOLE'), findsOneWidget);
    expect(find.byType(ChangePasswordScreen), findsNothing);
  });
}
