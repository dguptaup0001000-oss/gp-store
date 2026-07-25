import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/presentation/auth_providers.dart';
import '../../features/auth/presentation/login_screen.dart';
import '../../features/auth/presentation/otp_login_screen.dart';
import '../../features/auth/presentation/register_screen.dart';
import '../../features/home_screen.dart';

/// Auth-gated routing: unauthenticated users are redirected to /login no
/// matter what path they try to hit; authenticated users are redirected
/// away from /login, /login/otp, and /register (no reason to see those once
/// logged in). This is what makes logout/session-expiry (see api_client.dart)
/// actually navigate somewhere, instead of just quietly clearing state.
final appRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/',
    refreshListenable: _AuthStateNotifier(ref),
    redirect: (context, state) {
      // ref.read, not watch - this Provider body only runs ONCE (see below
      // for why), so reading here means "give me the current value right
      // now", which is exactly what a redirect check needs.
      final authState = ref.read(authControllerProvider);
      final isAuthRoute = state.matchedLocation == '/login' ||
          state.matchedLocation == '/login/otp' ||
          state.matchedLocation == '/register';

      // Still checking stored session - show nothing/splash rather than
      // flash the wrong screen and immediately redirect.
      if (authState.status == AuthStatus.unknown) {
        return null;
      }

      final isLoggedIn = authState.status == AuthStatus.authenticated;

      if (!isLoggedIn && !isAuthRoute) return '/login';
      if (isLoggedIn && isAuthRoute) return '/';

      return null;
    },
    routes: [
      GoRoute(path: '/', builder: (context, state) => const HomeScreen()),
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
