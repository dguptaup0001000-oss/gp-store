import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Wraps flutter_secure_storage (Keychain on iOS, Keystore on Android).
/// The refresh token in particular must NEVER go in SharedPreferences/plain
/// storage - it's a long-lived credential, exactly the kind of thing secure
/// storage exists for.
class TokenStorage {
  // Without resetOnError, a Keystore-backed entry that becomes undecryptable
  // (a known Android issue after certain OS security-patch cycles, backup/
  // restore, or on some OEM Keystore implementations - Xiaomi/Samsung/Oppo
  // are the commonly reported ones) makes EVERY future read() throw
  // indefinitely, with nothing in this app ever clearing it - the user would
  // see "Couldn't load your account" on every launch until something
  // external resets it (reboot, reinstall). resetOnError makes a corrupted
  // entry self-heal: the plugin wipes the unreadable value and read() just
  // returns null (same as "never logged in") instead of throwing forever.
  // encryptedSharedPreferences avoids the older, more failure-prone Keystore
  // storage path entirely on Android versions that support it.
  TokenStorage()
      : _storage = const FlutterSecureStorage(
          aOptions: AndroidOptions(resetOnError: true, encryptedSharedPreferences: true),
        );

  final FlutterSecureStorage _storage;

  static const _accessTokenKey = 'access_token';
  static const _refreshTokenKey = 'refresh_token';
  static const _rememberMeKey = 'remember_me';

  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    await _storage.write(key: _accessTokenKey, value: accessToken);
    await _storage.write(key: _refreshTokenKey, value: refreshToken);
  }

  Future<String?> getAccessToken() => _storage.read(key: _accessTokenKey);

  Future<String?> getRefreshToken() => _storage.read(key: _refreshTokenKey);

  /// Whether the current session should survive an app restart. Tokens are
  /// always written on login so in-progress API calls keep working either
  /// way - this flag is only consulted by AuthController on cold start to
  /// decide whether to restore that session or clear it. Absent (e.g. a
  /// session from before this flag existed, or the OTP/register flows which
  /// don't expose the toggle) defaults to true, matching the app's prior
  /// always-remember behavior.
  Future<void> setRememberMe(bool remember) => _storage.write(key: _rememberMeKey, value: remember.toString());

  Future<bool> getRememberMe() async => (await _storage.read(key: _rememberMeKey)) != 'false';

  Future<void> clear() async {
    await _storage.delete(key: _accessTokenKey);
    await _storage.delete(key: _refreshTokenKey);
    await _storage.delete(key: _rememberMeKey);
  }
}
