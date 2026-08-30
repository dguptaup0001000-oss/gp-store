import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/util/app_haptics.dart';
import '../../features/auth/presentation/auth_providers.dart';
import '../../features/profile/domain/profile_models.dart';
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
