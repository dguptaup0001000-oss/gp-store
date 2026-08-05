import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/profile_repository.dart';
import '../domain/profile_models.dart';

final profileRepositoryProvider = Provider<ProfileRepository>((ref) {
  return ProfileRepository(apiClient: ref.watch(apiClientProvider));
});

final myProfileProvider = FutureProvider<Profile>((ref) {
  // Re-fetch fresh whenever auth state changes (login, logout, a role
  // promotion taking effect on next login, etc.) - without this dependency,
  // Riverpod has no reason to know this needs re-running, since nothing else
  // it watches changes across a login/logout cycle. Without it, this kept
  // serving the FIRST successful fetch from the app's entire lifetime, even
  // after the underlying account had genuinely changed (e.g. a customer
  // promoted to ADMIN directly in the database wouldn't see the admin menu
  // appear until a full app restart, not just logging out and back in).
  ref.watch(authControllerProvider);
  return ref.watch(profileRepositoryProvider).getMyProfile();
});
