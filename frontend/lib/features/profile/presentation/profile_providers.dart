import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/profile_repository.dart';
import '../domain/profile_models.dart';

final profileRepositoryProvider = Provider<ProfileRepository>((ref) {
  return ProfileRepository(apiClient: ref.watch(apiClientProvider));
});

/// The signed-in customer's own profile.
///
/// TIED TO AUTH STATE ON PURPOSE. This provider used to depend on nothing,
/// so it ran whenever RootScreen first built and then cached whatever it got
/// - including a 401 fetched before session restoration had produced a token.
/// Nothing invalidated it afterwards, so that stale failure was served to the
/// authenticated session that followed, and the customer kept reading
/// "Couldn't load your account: Authentication required" while holding a
/// perfectly valid token. Signing out and back in was the only cure, which is
/// exactly the symptom that was reported.
///
/// Watching the auth STATUS (not the whole state, so an unrelated field
/// changing does not refetch) means every transition - restored, logged in,
/// logged out - throws the old result away and asks again. A failure from one
/// session can no longer be shown to the next.
final myProfileProvider = FutureProvider<Profile>((ref) {
  final status = ref.watch(authControllerProvider.select((state) => state.status));

  // Belt and braces with the router's splash redirect: even if some future
  // screen manages to read this before a session exists, it must not fire a
  // protected request that can only 401. The router keeps every protected
  // screen unmounted until restoration finishes, so in practice this never
  // runs - it is here so that guarantee does not live in only one place.
  if (status != AuthStatus.authenticated) {
    return Future<Profile>.error(
      const NotSignedInException(),
      StackTrace.current,
    );
  }

  return ref.watch(profileRepositoryProvider).getMyProfile();
});

/// Distinguishes "there is no session yet" from "the server refused us".
///
/// Never rendered as an error: RootScreen treats it as a loading state,
/// because it only ever means the answer is not in yet.
class NotSignedInException implements Exception {
  const NotSignedInException();

  @override
  String toString() => 'Not signed in yet';
}
