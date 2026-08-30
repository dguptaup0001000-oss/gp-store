import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_theme.dart';
import '../../core/util/haptic_widgets.dart';
import '../../features/auth/presentation/auth_providers.dart';

/// Shown when a valid session belongs to a different GP-STORE application.
///
/// The two APKs share one backend. A shopper JWT in the admin app (or an
/// admin JWT in the customer app) is not a bug in auth - it is the wrong
/// install. Sign-out here only clears this app's storage.
class WrongAppScreen extends ConsumerWidget {
  const WrongAppScreen({
    super.key,
    required this.title,
    required this.message,
  });

  final String title;
  final String message;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.phonelink_erase_outlined,
                size: 48, color: AppColors.primary),
            const SizedBox(height: 16),
            Text(
              message,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: hapticize(
                  () => ref.read(authControllerProvider.notifier).logout()),
              child: const Text('Sign out'),
            ),
          ],
        ),
      ),
    );
  }
}
