import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/auth/presentation/auth_providers.dart';
import '../features/profile/domain/profile_models.dart';
import '../shared/widgets/signed_in_home.dart';
import '../shared/widgets/wrong_app_screen.dart';
import 'auth/admin_permissions.dart';
import 'shell/admin_destinations.dart';
import 'shell/admin_shell.dart';
import 'shell/shop_switcher_bar.dart';

/// Admin APK home. Staff tools only - no shopping shell.
class AdminRootScreen extends ConsumerWidget {
  const AdminRootScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SignedInHome(
      builder: (profile) => _homeFor(ref, profile),
    );
  }

  Widget _homeFor(WidgetRef ref, Profile profile) {
    // ANY STAFF ROLE, not just ADMIN. Gating on the single string 'ADMIN'
    // would lock every new role out of the console entirely - a MANAGER
    // would install the admin APK and be told to go and shop.
    if (!AdminRoles.isStaff(profile.role)) {
      return const WrongAppScreen(
        title: 'GP-STORE Admin',
        message:
            'This app is for authorized store staff only. '
            'Install GP-STORE to shop.',
      );
    }
    return AdminShell(
      navigation: AdminNav.navigation,
      operatorName: profile.fullName,
      role: profile.role,
      shopSwitcher: profile.role.trim().toUpperCase() == AdminRoles.admin
          ? const ShopSwitcherBar()
          : null,
      onSignOut: () => ref.read(authControllerProvider.notifier).logout(),
    );
  }
}
