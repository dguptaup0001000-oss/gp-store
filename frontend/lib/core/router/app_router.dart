import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/presentation/auth_providers.dart';
import '../../features/auth/presentation/forgot_password_screen.dart';
import '../../features/auth/presentation/login_screen.dart';
import '../../features/auth/presentation/otp_login_screen.dart';
import '../../features/auth/presentation/splash_screen.dart';

/// Lets code without a local BuildContext (the FCM notification-tap handler)
/// still push a screen on top of whatever the user is currently looking at.
/// Must be wired into GoRouter itself - setting this on MaterialApp.router
/// directly has no effect once a routerConfig is supplied.
final rootNavigatorKey = GlobalKey<NavigatorState>();

/// Auth-gated router shared by the customer and admin APKs.
///
/// [home] is the signed-in landing widget for that APK. The admin build
/// omits `/register` so the staff app cannot open customer signup.
GoRouter createGoRouter({
  required Ref ref,
  required Widget home,
  bool allowRegister = true,
  List<RouteBase> extraRoutes = const [],
}) {
  return GoRouter(
    navigatorKey: rootNavigatorKey,
    initialLocation: '/',
    refreshListenable: AuthRefreshNotifier(ref),
    redirect: (context, state) {
      final authState = ref.read(authControllerProvider);
      return resolveStartupRedirect(
        status: authState.status,
        location: state.matchedLocation,
        allowRegister: allowRegister,
      );
    },
    routes: [
      GoRoute(path: '/', builder: (context, state) => home),
      GoRoute(path: '/splash', builder: (context, state) => const SplashScreen()),
      GoRoute(
        path: '/login',
        builder: (context, state) => LoginScreen(allowRegister: allowRegister),
      ),
      GoRoute(path: '/login/otp', builder: (context, state) => const OtpLoginScreen()),
      GoRoute(
          path: '/login/forgot',
          builder: (context, state) => const ForgotPasswordScreen()),
      ...extraRoutes,
    ],
  );
}

/// Bridges Riverpod's state changes into something go_router's
/// refreshListenable can listen to, so the router re-evaluates redirects
/// the instant auth state changes (login, logout, or a forced session
/// expiry) - without this, the router wouldn't know to react.
class AuthRefreshNotifier extends ChangeNotifier {
  AuthRefreshNotifier(Ref ref) {
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
  bool allowRegister = true,
}) {
  final isAuthRoute =
      location == '/login' ||
      location == '/login/otp' ||
      location == '/login/forgot' ||
      (allowRegister && location == '/register');
  final isSplashRoute = location == '/splash';

  // STILL CHECKING THE STORED SESSION - go to the splash, and note that
  // returning null here does NOT do that.
  //
  // null means "no redirect", so go_router renders the route that was asked
  // for. On a cold start that is '/', which watches myProfileProvider, which
  // issues GET /api/customers/me before restoration has produced a token.
  // The backend answers correctly:
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
