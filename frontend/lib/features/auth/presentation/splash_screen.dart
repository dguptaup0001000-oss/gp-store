import 'package:flutter/material.dart';

/// What the app shows while it works out whether you are signed in.
///
/// THIS SCREEN IS THE FIX, not decoration. The router used to return null
/// from its redirect while AuthStatus was still `unknown`, and null means
/// "no redirect" - so go_router rendered the requested route, which is
/// RootScreen. RootScreen watches myProfileProvider, which immediately
/// issues GET /api/customers/me. Session restoration had not finished, so
/// no token was attached, and the backend answered exactly what it should:
///
///     401 {"message":"Authentication required"}
///
/// The customer saw "Couldn't load your account: Authentication required" on
/// a first launch, for no reason other than reading secure storage taking a
/// moment.
///
/// Redirecting here instead means no protected screen can mount before the
/// answer is known. The window is short - one secure-storage read - so this
/// is usually a single frame, and deliberately quiet rather than an animated
/// splash that would draw attention to a wait nobody should notice.
class SplashScreen extends StatelessWidget {
  const SplashScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'GP-Store',
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w700,
                    color: Theme.of(context).colorScheme.primary,
                  ),
            ),
            const SizedBox(height: 20),
            const SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ],
        ),
      ),
    );
  }
}
