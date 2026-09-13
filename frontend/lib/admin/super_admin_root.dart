import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/auth/presentation/auth_providers.dart';
import '../features/profile/domain/profile_models.dart';
import '../shared/widgets/signed_in_home.dart';
import '../shared/widgets/wrong_app_screen.dart';
import 'auth/admin_permissions.dart';
import 'shell/admin_destinations.dart';
import 'shell/admin_shell.dart';

/// Super Admin APK home. The platform owner's console.
///
/// WHAT MAKES IT DIFFERENT FROM THE ADMIN APK, and it is only two things:
/// it opens on Merchants & Shops instead of a shop dashboard, and it refuses
/// an account that is not the platform owner. Everything behind it is the
/// same shell and the same screens - forking twenty-five working screens to
/// give the owner a different front door would be a poor trade.
///
/// NOT A SECURITY BOUNDARY. Every route this app calls is gated server-side
/// on PERM_PLATFORM_ADMIN, which no shop role holds, so a merchant who
/// sideloads this APK is refused by the backend on every screen in it. The
/// refusal below is about not showing somebody a console that can only 403 -
/// the same reason the sidebar hides destinations a role cannot use.
class SuperAdminRootScreen extends ConsumerWidget {
  const SuperAdminRootScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SignedInHome(
      builder: (profile) => _homeFor(ref, profile),
    );
  }

  Widget _homeFor(WidgetRef ref, Profile profile) {
    // THE PERMISSION, NOT THE ROLE STRING. platformAdmin is the one
    // permission no shop role holds - RolePermissions builds every shop role
    // by subtracting it - so this stays correct if a future platform-level
    // role is added, and it treats the PLATFORM_ADMIN alias exactly like
    // SUPER_ADMIN without naming it twice.
    final permissions = AdminRoles.permissionsFor(profile.role);
    if (!permissions.contains(AdminPermission.platformAdmin)) {
      return const WrongAppScreen(
        title: 'GP-STORE Super Admin',
        message:
            'This app is for the GP-STORE platform owner.\n\n'
            'To run your own shop, install GP-STORE Admin and sign in with '
            'the same email and password.',
      );
    }
    return AdminShell(
      home: AdminNav.platformConsole,
      operatorName: profile.fullName,
      role: profile.role,
      onSignOut: () => ref.read(authControllerProvider.notifier).logout(),
    );
  }
}
