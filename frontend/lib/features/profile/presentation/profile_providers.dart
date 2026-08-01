import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/profile_repository.dart';
import '../domain/profile_models.dart';

final profileRepositoryProvider = Provider<ProfileRepository>((ref) {
  return ProfileRepository(apiClient: ref.watch(apiClientProvider));
});

final myProfileProvider = FutureProvider<Profile>((ref) {
  return ref.watch(profileRepositoryProvider).getMyProfile();
});
