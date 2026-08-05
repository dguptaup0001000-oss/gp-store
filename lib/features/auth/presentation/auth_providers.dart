import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/api_client.dart';
import '../../../core/storage/token_storage.dart';
import '../data/auth_repository.dart';
import '../domain/auth_models.dart';

final tokenStorageProvider = Provider<TokenStorage>((ref) => TokenStorage());

final Provider<ApiClient> apiClientProvider = Provider<ApiClient>((ref) {
  final tokenStorage = ref.watch(tokenStorageProvider);

  return ApiClient(
    tokenStorage: tokenStorage,
    // When even the refresh token turns out to be invalid, force the auth
    // state back to unauthenticated so the router redirects to login -
    // ref.read (not watch) because this fires from inside a callback, not
    // during provider construction.
    onSessionExpired: () {
      ref.read(authControllerProvider.notifier).forceLogout();
    },
  );
});

final Provider<AuthRepository> authRepositoryProvider = Provider<AuthRepository>((ref) {
  return AuthRepository(
    apiClient: ref.watch(apiClientProvider),
    tokenStorage: ref.watch(tokenStorageProvider),
  );
});

enum AuthStatus { unknown, authenticated, unauthenticated }

class AuthState {
  const AuthState({required this.status, this.user, this.errorMessage});

  final AuthStatus status;
  final AuthResponse? user;
  final String? errorMessage;

  AuthState copyWith({AuthStatus? status, AuthResponse? user, String? errorMessage}) {
    return AuthState(
      status: status ?? this.status,
      user: user ?? this.user,
      errorMessage: errorMessage,
    );
  }
}

/// The single source of truth for "is someone logged in right now" - the
/// router (app_router.dart) watches this to decide whether to show the
/// login screen or the main app.
class AuthController extends StateNotifier<AuthState> {
  AuthController(this._repository) : super(const AuthState(status: AuthStatus.unknown)) {
    _restoreSession();
  }

  final AuthRepository _repository;

  Future<void> _restoreSession() async {
    // Optimistic: if a refresh token exists locally, assume authenticated
    // and let the API client's 401 handling correct this reactively if it
    // turns out to be invalid/expired. Avoids an extra network round-trip
    // just to check on every app launch.
    final hasSession = await _repository.hasStoredSession();
    state = AuthState(status: hasSession ? AuthStatus.authenticated : AuthStatus.unauthenticated);
  }

  Future<bool> login({required String email, required String password}) async {
    state = state.copyWith(status: AuthStatus.unknown, errorMessage: null);
    try {
      final auth = await _repository.login(email: email, password: password);
      state = AuthState(status: AuthStatus.authenticated, user: auth);
      return true;
    } catch (e) {
      state = AuthState(
        status: AuthStatus.unauthenticated,
        errorMessage: extractErrorMessage(e),
      );
      return false;
    }
  }

  Future<bool> register({
    required String name,
    required String email,
    required String phone,
    required String password,
  }) async {
    state = state.copyWith(status: AuthStatus.unknown, errorMessage: null);
    try {
      final auth = await _repository.register(
        name: name,
        email: email,
        phone: phone,
        password: password,
      );
      state = AuthState(status: AuthStatus.authenticated, user: auth);
      return true;
    } catch (e) {
      state = AuthState(
        status: AuthStatus.unauthenticated,
        errorMessage: extractErrorMessage(e),
      );
      return false;
    }
  }

  Future<void> logout() async {
    await _repository.logout();
    state = const AuthState(status: AuthStatus.unauthenticated);
  }

  /// Signs out of every device, not just this one.
  Future<void> logoutAllDevices() async {
    await _repository.logoutAllDevices();
    state = const AuthState(status: AuthStatus.unauthenticated);
  }

  /// Called by the OTP flow (otp_providers.dart) once verification succeeds -
  /// AuthController stays the single source of truth for "who is logged in",
  /// even though the OTP flow itself lives in its own screen-local controller.
  void setAuthenticated(AuthResponse auth) {
    state = AuthState(status: AuthStatus.authenticated, user: auth);
  }

  /// Called by the API client when even the refresh token has expired/been
  /// revoked - no server call here (it would just fail again), just reset
  /// local state so the UI reacts.
  void forceLogout() {
    state = const AuthState(status: AuthStatus.unauthenticated);
  }
}

final StateNotifierProvider<AuthController, AuthState> authControllerProvider =
    StateNotifierProvider<AuthController, AuthState>((ref) {
  return AuthController(ref.watch(authRepositoryProvider));
});

/// Unwraps the REAL backend error message (set by ApiClient._mapToApiException)
/// instead of showing a generic Dart exception description like
/// "DioException [bad response]: ..." to the user.
String extractErrorMessage(Object error) {
  if (error is DioException && error.error is ApiException) {
    return (error.error as ApiException).message;
  }
  // TEMPORARY, for active debugging - any error that ISN'T a network-level
  // DioException (e.g. a JSON parsing failure when a response body doesn't
  // match what the frontend model expects) was being silently replaced with
  // an unhelpful generic string here, discarding the actual reason. Once
  // real bugs surfaced this way are found and fixed, revert to the plain
  // generic fallback.
  return 'Something went wrong: $error';
}
