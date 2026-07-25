import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'delivery/presentation/delivery_dashboard_screen.dart';
import 'home/presentation/home_screen.dart';
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
      error: (error, stackTrace) => Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text("Couldn't load your account - check your connection"),
              TextButton(onPressed: () => ref.invalidate(myProfileProvider), child: const Text('Retry')),
            ],
          ),
        ),
      ),
      data: (profile) {
        // CUSTOMER and ADMIN both land on the shopping experience - an admin
        // still needs to browse as a shopper, and reaches Store Management
        // through their Profile tab, same as before this change.
        if (profile.role == 'DELIVERY_BOY') {
          return const DeliveryDashboardScreen();
        }
        return const HomeScreen();
      },
    );
  }
}
