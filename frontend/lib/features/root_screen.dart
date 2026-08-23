import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/util/app_haptics.dart';
import 'auth/presentation/auth_providers.dart';
import 'delivery/presentation/delivery_dashboard_screen.dart';
import 'customer_shell.dart';
import 'profile/presentation/profile_providers.dart';

/// Decides which "home" a logged-in user actually sees. Deliberately reads
/// the freshly-fetched Profile.role rather than AuthState.user.role - the
/// latter is only populated right after a fresh login/OTP-verify and stays
/// null after an app restart (session restore only checks for a stored
/// token, it doesn't refetch identity) - same lesson learned building the
/// admin gate in ProfileScreen.
class RootScreen extends ConsumerWidget {
  const RootScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profileAsync = ref.watch(myProfileProvider);

    return profileAsync.when(
      loading: () => const Scaffold(body: Center(child: CircularProgressIndicator(strokeWidth: 2))),
      error: (error, stackTrace) {
        // NOT AN ERROR - the answer simply is not in yet. myProfileProvider
        // reports this when no session exists, which can only happen in the
        // instant before the router's splash redirect takes effect. Painting
        // a failure here is what produced "Couldn't load your account:
        // Authentication required" on a first launch.
        if (error is NotSignedInException) {
          return const Scaffold(body: Center(child: CircularProgressIndicator(strokeWidth: 2)));
        }

        // A real failure. The raw backend text used to be printed here while
        // this bug was being chased - useful then, wrong to ship: a customer
        // cannot act on "Authentication required", and it says more about the
        // stack than they need to know. What they CAN act on is whether the
        // shop or their connection is at fault, which is what
        // extractErrorMessage now answers (see error_messages.dart).
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
      data: (profile) {
        // CUSTOMER and ADMIN both land on the shopping experience - an admin
        // still needs to browse as a shopper, and reaches Store Management
        // through their Profile tab, same as before this change.
        if (profile.role == 'DELIVERY_BOY') {
          return const DeliveryDashboardScreen();
        }
        return const CustomerShell();
      },
    );
  }
}
