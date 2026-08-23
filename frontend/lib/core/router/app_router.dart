import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/presentation/auth_providers.dart';
import '../../features/auth/presentation/login_screen.dart';
import '../../features/auth/presentation/otp_login_screen.dart';
import '../../features/auth/presentation/register_screen.dart';
import '../../features/auth/presentation/splash_screen.dart';
import '../../features/root_screen.dart';

/// Lets code without a local BuildContext (the FCM notification-tap handler
/// in main.dart) still push a screen on top of whatever the user is
/// currently looking at. Must be wired into GoRouter itself below - setting
/// this on MaterialApp.router directly has no effect once a routerConfig
/// is supplied.
final rootNavigatorKey = GlobalKey<NavigatorState>();

/// Auth-gated routing: unauthenticated users are redirected to /login no
/// matter what path they try to hit; authenticated users are redirected
/// away from /login, /login/otp, and /register (no reason to see those once
/// logged in). This is what makes logout/session-expiry (see api_client.dart)
/// actually navigate somewhere, instead of just quietly clearing state.
final appRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    navigatorKey: rootNavigatorKey,
    initialLocation: '/',
    refreshListenable: _AuthStateNotifier(ref),
    redirect: (context, state) {
      // ref.read, not watch - this Provider body only runs ONCE (see below
      // for why), so reading here means "give me the current value right
      // now", which is exactly what a redirect check needs.
      final authState = ref.read(authControllerProvider);
      return resolveStartupRedirect(
        status: authState.status,
        location: state.matchedLocation,
      );
    },
    routes: [
      GoRoute(path: '/', builder: (context, state) => const RootScreen()),
      GoRoute(path: '/splash', builder: (context, state) => const SplashScreen()),
      GoRoute(path: '/login', builder: (context, state) => const LoginScreen()),
      GoRoute(path: '/login/otp', builder: (context, state) => const OtpLoginScreen()),
      GoRoute(path: '/register', builder: (context, state) => const RegisterScreen()),
    ],
  );
});

/// Bridges Riverpod's state changes into something go_router's
/// refreshListenable can listen to, so the router re-evaluates redirects
/// the instant auth state changes (login, logout, or a forced session
/// expiry) - without this, the router wouldn't know to react.
class _AuthStateNotifier extends ChangeNotifier {
  _AuthStateNotifier(Ref ref) {
    ref.listen(authControllerProvider, (_, __) => notifyListeners());
  }
}

/// Where a request for [location] should actually go, given [status].
///
/// A PURE FUNCTION ON PURPOSE. The whole first-launch bug was one branch of
/// this table returning the wrong thing, and a test that needed a booted app
/// and a live backend to notice is a test nobody writes. Pulled out here so
/// startup_auth_test.dart can exercise the real decision rather than a copy
/// of it.
///
/// Returns null for "stay where you are".
String? resolveStartupRedirect({
  required AuthStatus status,
  required String location,
}) {
  final isAuthRoute =
      location == '/login' || location == '/login/otp' || location == '/register';
  final isSplashRoute = location == '/splash';

  // STILL CHECKING THE STORED SESSION - go to the splash, and note that
  // returning null here does NOT do that.
  //
  // null means "no redirect", so go_router renders the route that was asked
  // for. On a cold start that is '/', which is RootScreen, which watches
  // myProfileProvider, which issues GET /api/customers/me before restoration
  // has produced a token. The backend answers correctly:
  //
  //     401 {"message":"Authentication required"}
  //
  // and the customer reads "Couldn't load your account: Authentication
  // required" on a first launch. The old comment here always said "show
  // nothing/splash"; the code never did it.
  //
  // AUTH ROUTES ARE EXEMPT, and that is not a special case bolted on -
  // `unknown` means two different things in this controller. It is both
  // "restoring the stored session" (set in the constructor) and "a login is
  // in flight" (set at the top of login/verifyOtp). Without the exemption,
  // pressing Sign In would redirect the customer off the login screen to the
  // splash and back, discarding the form's own progress state every time.
  // Splitting the enum would express it better; leaving it alone keeps this
  // change to the routing bug it is meant to fix.
  if (status == AuthStatus.unknown) {
    return (isSplashRoute || isAuthRoute) ? null : '/splash';
  }

  final isLoggedIn = status == AuthStatus.authenticated;

  // Restoration finished. Nobody may sit on the splash afterwards, or a slow
  // start would strand them on a spinner forever.
  if (isSplashRoute) return isLoggedIn ? '/' : '/login';

  if (!isLoggedIn && !isAuthRoute) return '/login';
  if (isLoggedIn && isAuthRoute) return '/';

  return null;
}
