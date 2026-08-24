/// Which deployment this build talks to.
///
/// The base URL was already in one place - a single const in ApiClient,
/// overridable with --dart-define=API_BASE_URL - so this is not a rescue from
/// URLs scattered through repositories. What it adds is a NAMED environment,
/// which is the difference between "the URL happens to be right" and "this
/// build is a production build and says so".
///
/// That distinction earns its keep at the moment of the Oracle migration:
/// production's URL changes in exactly one place here, and nothing else in
/// the app needs to know a host moved.
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
  /// Override with `--dart-define=API_BASE_URL=https://api.example/v1` so a
  /// future stable host (for example api.gpstore.co.in) does not require
  /// scattered URL edits. Production's coded default remains the current
  /// Render service until that host is live and pointed here via CI's
  /// `API_BASE_URL` variable — not hardcoded throughout the app.
  String get baseUrl {
    const override = String.fromEnvironment('API_BASE_URL');
    if (override.isNotEmpty) return override;

    switch (this) {
      case AppEnvironment.production:
        return 'https://gp-store.onrender.com/v1';
      case AppEnvironment.staging:
        return 'https://gp-store.onrender.com/v1';
      case AppEnvironment.development:
        // 10.0.2.2 is the Android emulator's alias for the host machine's
        // own localhost - not a typo. Use the machine's LAN IP for a
        // physical device.
        return 'http://10.0.2.2:8081/v1';
    }
  }

  /// How long to wait on a request before giving up.
  ///
  /// 45s for anything pointed at Render, and that is not timidity: the free
  /// tier spins the backend down after ~15 minutes idle and takes 30-60s to
  /// cold-start, so a shorter timeout makes the first request after any quiet
  /// period fail while the backend is merely booting.
  ///
  /// It is a bad number for an always-on host. When production moves to
  /// Oracle, drop it to ~15s: a customer staring at a spinner for 45 seconds
  /// has already decided the app is broken.
  Duration get timeout {
    const seconds = int.fromEnvironment('API_TIMEOUT_SECONDS', defaultValue: 0);
    if (seconds > 0) return Duration(seconds: seconds);
    return const Duration(seconds: 45);
  }

  /// Whether to print diagnostics. Off in production, so nothing about a
  /// request can reach a device log there.
  bool get verboseLogging => this != AppEnvironment.production;
}
