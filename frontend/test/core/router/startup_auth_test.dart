import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/router/app_router.dart';
import 'package:gpstore/features/auth/presentation/auth_providers.dart';

/// The startup sequence, expressed as the rules the router must obey.
///
/// THE BUG. While AuthStatus was `unknown` the redirect returned null, and
/// null means "no redirect" - so go_router rendered the requested route. On a
/// cold start that is '/', which is RootScreen, which watches
/// myProfileProvider, which issues GET /api/customers/me before session
/// restoration has produced a token. The backend answered 401 correctly and
/// the customer read "Couldn't load your account: Authentication required" on
/// a first launch.
///
/// These tests call the REAL redirect (app_router.dart's
/// resolveStartupRedirect), not a copy of it. The whole defect was one branch
/// of that table returning the wrong thing, and a test asserting against a
/// duplicate of the logic would have passed just as happily while the app
/// shipped broken.
void main() {
  group('while the stored session is still being read', () {
    test('a cold start goes to the splash, never to a protected screen', () {
      // The regression itself. Returning null here renders RootScreen, which
      // fires a protected request with no token.
      expect(resolveStartupRedirect(status: AuthStatus.unknown, location: '/'), '/splash');
    });

    test('the splash does not redirect to itself', () {
      expect(resolveStartupRedirect(status: AuthStatus.unknown, location: '/splash'), isNull);
    });

    test('a login in flight stays on the login screen', () {
      // `unknown` means two things in this controller: restoring a session,
      // and a login in flight. Without this exemption, pressing Sign In would
      // throw the customer off the form and discard its progress state.
      expect(resolveStartupRedirect(status: AuthStatus.unknown, location: '/login'), isNull);
      expect(resolveStartupRedirect(status: AuthStatus.unknown, location: '/login/otp'), isNull);
      expect(resolveStartupRedirect(status: AuthStatus.unknown, location: '/register'), isNull);
    });
  });

  group('once the answer is known', () {
    test('a restored session leaves the splash for home', () {
      expect(resolveStartupRedirect(status: AuthStatus.authenticated, location: '/splash'), '/');
    });

    test('no session leaves the splash for login', () {
      // Nobody may be stranded on a spinner after restoration finishes.
      expect(resolveStartupRedirect(status: AuthStatus.unauthenticated, location: '/splash'), '/login');
    });

    test('a signed-out customer cannot reach a protected route', () {
      expect(resolveStartupRedirect(status: AuthStatus.unauthenticated, location: '/'), '/login');
    });

    test('a signed-in customer is not shown the login screen', () {
      expect(resolveStartupRedirect(status: AuthStatus.authenticated, location: '/login'), '/');
    });

    test('a signed-in customer on home stays put', () {
      expect(resolveStartupRedirect(status: AuthStatus.authenticated, location: '/'), isNull);
    });
  });

  test('no status/route pair can leave the app on the splash forever', () {
    // The failure mode a splash introduces if its exit is not total: a
    // spinner nobody ever leaves. Every terminal status must route away.
    for (final status in [AuthStatus.authenticated, AuthStatus.unauthenticated]) {
      expect(resolveStartupRedirect(status: status, location: '/splash'), isNotNull,
          reason: '$status must leave the splash');
    }
  });
}
