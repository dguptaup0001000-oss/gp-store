/// Which deployment this build talks to.
///
/// The base URL lives in one place - this file - overridable with
/// `--dart-define=API_BASE_URL`. That is the difference between "the URL
/// happens to be right" and "this build is a production build and says so".
///
/// The machine that runs Spring Boot is not an API and must never appear
/// in this app as an SDK or extra HTTP client. The APK only talks to
/// [productionApiBaseUrl] (or an override).
///
/// Selected at build time:
///   flutter build apk --dart-define=APP_ENV=production
///
/// API_BASE_URL still overrides the URL for any environment, which is how a
/// developer points a debug build at a laptop or a branch deployment without
/// inventing an environment for it.
enum AppEnvironment {
  development,
  staging,
  production;

  static const _envName = String.fromEnvironment('APP_ENV', defaultValue: 'development');

  /// Canonical production API. Must stay HTTPS and end with `/v1`.
  ///
  /// Hostinger VPS + Traefik. Never Render or localhost.
  /// Override only for a laptop/debug build via `--dart-define=API_BASE_URL`.
  static const String productionApiBaseUrl = 'https://api.gpstore.co.in/v1';

  /// The environment this build was compiled for.
  ///
  /// An unrecognised APP_ENV falls back to development rather than throwing:
  /// a typo should produce a build that obviously points at localhost, not
  /// one that fails to start, and certainly not one that silently assumes
  /// production.
  static AppEnvironment get current {
    switch (_envName) {
      case 'production':
        return AppEnvironment.production;
      case 'staging':
        return AppEnvironment.staging;
      default:
        return AppEnvironment.development;
    }
  }

  bool get isProduction => this == AppEnvironment.production;

  /// Where this build's API lives.
  ///
  /// Override with `--dart-define=API_BASE_URL=https://your-host/v1`.
  String get baseUrl {
    const override = String.fromEnvironment('API_BASE_URL');
    if (override.isNotEmpty) return override;

    switch (this) {
      case AppEnvironment.production:
      case AppEnvironment.staging:
        return productionApiBaseUrl;
      case AppEnvironment.development:
        // 10.0.2.2 is the Android emulator's alias for the host machine's
        // own localhost - not a typo. Use the machine's LAN IP for a
        // physical device.
        return 'http://10.0.2.2:8081/v1';
    }
  }

  /// How long to wait on a request before giving up.
  ///
  /// 15s on an always-on VPS. A customer staring at a spinner longer than
  /// that has already decided the app is broken. Override with
  /// `--dart-define=API_TIMEOUT_SECONDS=...` if a specific build needs more.
  Duration get timeout {
    const seconds = int.fromEnvironment('API_TIMEOUT_SECONDS', defaultValue: 0);
    if (seconds > 0) return Duration(seconds: seconds);
    return const Duration(seconds: 15);
  }

  /// Whether to print diagnostics. Off in production, so nothing about a
  /// request can reach a device log there.
  bool get verboseLogging => this != AppEnvironment.production;
}
