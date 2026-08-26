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
  TokenStorage({this.keyPrefix = ''})
      : _storage = const FlutterSecureStorage(
          aOptions: AndroidOptions(
              resetOnError: true, encryptedSharedPreferences: true),
        );

  /// Isolates worker tokens from the customer app when both are installed.
  /// Different applicationIds already sandbox storage; the prefix is extra
  /// protection if a build ever shared an id by mistake.
  final String keyPrefix;

  final FlutterSecureStorage _storage;

  String get _accessTokenKey => '${keyPrefix}access_token';
  String get _refreshTokenKey => '${keyPrefix}refresh_token';
  String get _rememberMeKey => '${keyPrefix}remember_me';

  /// In-memory copy of the access token.
  ///
  /// Secure storage remains the persistent source of truth - this only
  /// avoids paying for it on every single request. Every authenticated API
  /// call went through _storage.read(), and on Android that is a platform
  /// channel round trip plus a decrypt, on the critical path of literally
  /// every request the app makes. It is small per call and completely
  /// invisible in isolation, which is exactly why it went unnoticed while
  /// adding up across a screen that fires several requests.
  ///
  /// Correctness rules this must never break:
  ///  - written through on save, so the cache can never be staler than
  ///    storage;
  ///  - cleared on logout/session-expiry, so a signed-out app cannot keep
  ///    using a token that is no longer on disk;
  ///  - only ever holds the SHORT-LIVED access token. The refresh token is
  ///    deliberately still read from secure storage each time: it is the
  ///    long-lived credential, it is needed rarely (once per access-token
  ///    expiry), and keeping it out of a long-lived field limits how long it
  ///    sits in process memory for no measurable benefit.
  String? _cachedAccessToken;

  /// Distinguishes "not read yet" from "read, and there genuinely is none".
  /// Without this, a logged-out app would re-hit secure storage on every
  /// request looking for a token that is not there.
  bool _accessTokenLoaded = false;

  /// Refresh token held only in memory until [saveTokens] persists it.
  /// Used by worker login so a failed /me cannot leave credentials on disk.
  String? _heldRefreshToken;

  /// Makes the access token available to the API client without writing it
  /// to secure storage. Persist with [saveTokens] only after the session is
  /// verified; [clear] drops both memory and disk.
  void holdTokensInMemory({
    required String accessToken,
    required String refreshToken,
  }) {
    _cachedAccessToken = accessToken;
    _accessTokenLoaded = true;
    _heldRefreshToken = refreshToken;
  }

  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    // Storage first, cache second: if the write throws, the cache must not
    // be left claiming a token that was never persisted.
    await _storage.write(key: _accessTokenKey, value: accessToken);
    await _storage.write(key: _refreshTokenKey, value: refreshToken);
    _cachedAccessToken = accessToken;
    _accessTokenLoaded = true;
    _heldRefreshToken = null;
  }

  Future<String?> getAccessToken() async {
    if (_accessTokenLoaded) {
      return _cachedAccessToken;
    }
    _cachedAccessToken = await _storage.read(key: _accessTokenKey);
    _accessTokenLoaded = true;
    return _cachedAccessToken;
  }

  Future<String?> getRefreshToken() async {
    if (_heldRefreshToken != null) return _heldRefreshToken;
    return _storage.read(key: _refreshTokenKey);
  }

  /// Whether the current session should survive an app restart. Tokens are
  /// always written on login so in-progress API calls keep working either
  /// way - this flag is only consulted by AuthController on cold start to
  /// decide whether to restore that session or clear it. Absent (e.g. a
  /// session from before this flag existed, or the OTP/register flows which
  /// don't expose the toggle) defaults to true, matching the app's prior
  /// always-remember behavior.
  Future<void> setRememberMe(bool remember) =>
      _storage.write(key: _rememberMeKey, value: remember.toString());

  Future<bool> getRememberMe() async =>
      (await _storage.read(key: _rememberMeKey)) != 'false';

  Future<void> clear() async {
    // Cache first here, deliberately the opposite order to saveTokens: if a
    // delete throws partway, the in-memory token must already be gone.
    // Leaving it set would let a signed-out app keep authenticating with a
    // credential the user believes they revoked - a security failure, where
    // the reverse is only a redundant storage read.
    _cachedAccessToken = null;
    _accessTokenLoaded = true;
    _heldRefreshToken = null;

    await _storage.delete(key: _accessTokenKey);
    await _storage.delete(key: _refreshTokenKey);
    await _storage.delete(key: _rememberMeKey);
  }
}
