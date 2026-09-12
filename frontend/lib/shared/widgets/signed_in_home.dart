import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/error_messages.dart';
import '../../core/util/app_haptics.dart';
import '../../features/auth/presentation/auth_providers.dart';
import '../../features/profile/domain/profile_models.dart';
import '../../features/profile/presentation/change_password_screen.dart';
import '../../features/profile/presentation/profile_providers.dart';

/// Loading / error / profile shell shared by the customer and admin homes.
///
/// Does not decide which product UI to show - each APK passes its own
/// [builder] so the shop graph never imports admin screens and the admin
/// graph never imports [CustomerShell].
class SignedInHome extends ConsumerWidget {
  const SignedInHome({super.key, required this.builder});

  final Widget Function(Profile profile) builder;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profileAsync = ref.watch(myProfileProvider);

    return profileAsync.when(
      loading: () => const Scaffold(
          body: Center(child: CircularProgressIndicator(strokeWidth: 2))),
      error: (error, stackTrace) {
        if (error is NotSignedInException) {
          return const Scaffold(
              body: Center(child: CircularProgressIndicator(strokeWidth: 2)));
        }

        // THE ACCOUNT OWES A PASSWORD CHANGE, AND THIS IS WHERE THAT SURFACES.
        //
        // The platform opened this login and handed over a one-time password.
        // Until it is replaced, the backend refuses every route but
        // /api/auth/change-password - and /api/customers/me, which this
        // provider calls, is one of the refused ones. So the refusal itself is
        // the signal, and catching it here covers both cases with one branch:
        // a first sign-in on a freshly opened account, and a password the
        // platform reset while somebody was already signed in.
        //
        // Deliberately NOT driven off the login response's flag. That flag
        // exists (AuthResponse.mustChangePassword) and is honest, but a screen
        // that trusted only it would miss the mid-session reset and would show
        // the console over an API that answers 403 to everything.
        if (meansPasswordChangeRequired(error)) {
          return ChangePasswordScreen(
            forced: true,
            onChanged: () => ref.invalidate(myProfileProvider),
            onSignOut: () =>
                ref.read(authControllerProvider.notifier).logout(),
          );
        }

        return Scaffold(
          body: Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    extractErrorMessage(error),
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 8),
                  TextButton(
                    onPressed: () {
                      AppHaptics.selection();
                      ref.invalidate(myProfileProvider);
                    },
                    child: const Text('Retry'),
                  ),
                ],
              ),
            ),
          ),
        );
      },
      data: builder,
    );
  }
}
