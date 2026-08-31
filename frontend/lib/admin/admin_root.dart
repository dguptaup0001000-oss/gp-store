import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/auth/presentation/auth_providers.dart';
import '../features/profile/domain/profile_models.dart';
import '../shared/widgets/signed_in_home.dart';
import '../shared/widgets/wrong_app_screen.dart';
import 'shell/admin_shell.dart';

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
    if (profile.role != 'ADMIN') {
      return const WrongAppScreen(
        title: 'GP-STORE Admin',
        message:
            'This app is for authorized store staff only. '
            'Install GP-STORE to shop.',
      );
    }
    return AdminShell(
      operatorName: profile.fullName,
      onSignOut: () => ref.read(authControllerProvider.notifier).logout(),
    );
  }
}
