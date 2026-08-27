import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/config/app_environment.dart';

void main() {
  group('AppEnvironment', () {
    test('production points at a real https host, never localhost', () {
      // The mistake this catches is a shipped APK talking to 10.0.2.2, which
      // fails on every real phone while working perfectly on the emulator the
      // developer tested on.
      expect(AppEnvironment.production.baseUrl, startsWith('https://'));
      expect(AppEnvironment.production.baseUrl, isNot(contains('localhost')));
      expect(AppEnvironment.production.baseUrl, isNot(contains('10.0.2.2')));
      expect(AppEnvironment.production.baseUrl, isNot(contains('onrender')));
      expect(AppEnvironment.production.baseUrl, isNot(contains('render.com')));
      expect(AppEnvironment.production.baseUrl,
          AppEnvironment.productionApiBaseUrl);
      expect(AppEnvironment.production.baseUrl, 'https://api.gpstore.co.in/v1');
    });

    test('public privacy policy URL is https on GitHub Pages', () {
      expect(AppEnvironment.publicPrivacyPolicyUrl, startsWith('https://'));
      expect(AppEnvironment.publicPrivacyPolicyUrl, contains('privacy-policy.html'));
      expect(AppEnvironment.publicPrivacyPolicyUrl, isNot(contains('localhost')));
    });

    test(
        'staging without an API_BASE_URL override refuses to target production',
        () {
      expect(
        () => AppEnvironment.staging.baseUrl,
        throwsA(isA<StateError>().having(
          (e) => e.message,
          'message',
          contains('API_BASE_URL'),
        )),
      );
    });

    test('staging must not be identified as the live production host', () {
      expect(
          AppEnvironment.isProductionApiUrl(
              AppEnvironment.productionApiBaseUrl),
          isTrue);
      expect(
          AppEnvironment.isProductionApiUrl('https://staging.example.com/v1'),
          isFalse);
    });

    test('development and production target the versioned API path', () {
      // The backend serves under context-path /v1; a base URL without it
      // 404s every single request. Staging has no default URL by design.
      expect(AppEnvironment.production.baseUrl, endsWith('/v1'));
      expect(AppEnvironment.development.baseUrl, endsWith('/v1'));
    });

    test('production ignores a retired Render API_BASE_URL', () {
      // Login 404: CI baked vars.API_BASE_URL=https://gp-store.onrender.com/v1
      // into the APK. Render is gone, so POST /v1/api/auth/login is 404 text
      // and the snackbar is "Request failed (HTTP 404)".
      expect(
        AppEnvironment.canonicalizeProductionApiUrl(
            'https://gp-store.onrender.com/v1'),
        AppEnvironment.productionApiBaseUrl,
      );
      expect(
        AppEnvironment.canonicalizeProductionApiUrl(
            'https://gp-store.onrender.com/v1/'),
        AppEnvironment.productionApiBaseUrl,
      );
    });

    test('production requires https and /v1 on api.gpstore.co.in', () {
      expect(
        AppEnvironment.canonicalizeProductionApiUrl('https://api.gpstore.co.in'),
        AppEnvironment.productionApiBaseUrl,
      );
      expect(
        AppEnvironment.canonicalizeProductionApiUrl(
            'http://api.gpstore.co.in/v1'),
        AppEnvironment.productionApiBaseUrl,
      );
      expect(
        AppEnvironment.canonicalizeProductionApiUrl(
            'https://evil.example/v1'),
        AppEnvironment.productionApiBaseUrl,
      );
      expect(
        AppEnvironment.canonicalizeProductionApiUrl(
            AppEnvironment.productionApiBaseUrl),
        AppEnvironment.productionApiBaseUrl,
      );
      expect(
        AppEnvironment.canonicalizeProductionApiUrl(
            '${AppEnvironment.productionApiBaseUrl}/'),
        AppEnvironment.productionApiBaseUrl,
      );
      expect(
        AppEnvironment.canonicalizeProductionApiUrl(''),
        AppEnvironment.productionApiBaseUrl,
      );
    });

    test('development points at the host machine, not at production', () {
      // A debug build must never write to the real shop by accident.
      expect(AppEnvironment.development.baseUrl, contains('10.0.2.2'));
      expect(AppEnvironment.development.isProduction, isFalse);
    });

    test('only production is production', () {
      expect(AppEnvironment.production.isProduction, isTrue);
      expect(AppEnvironment.staging.isProduction, isFalse);
      expect(AppEnvironment.development.isProduction, isFalse);
    });

    test('production is silent', () {
      // Nothing about a request should reach a device log in production.
      expect(AppEnvironment.production.verboseLogging, isFalse);
      expect(AppEnvironment.development.verboseLogging, isTrue);
    });

    test('an unset APP_ENV resolves to development, not production', () {
      // Tests compile without --dart-define, so this is what current reads.
      // The rule being pinned: an absent or misspelled APP_ENV must fall back
      // to the harmless environment, never the live one.
      expect(AppEnvironment.current, AppEnvironment.development);
    });

    test('a release build with the default APP_ENV is refused', () {
      expect(
        () => AppEnvironment.assertReleaseBuildIsConfigured(isReleaseMode: true),
        throwsA(isA<StateError>().having(
          (e) => e.message,
          'message',
          contains('APP_ENV=production'),
        )),
      );
      expect(
        () => AppEnvironment.assertReleaseBuildIsConfigured(isReleaseMode: false),
        returnsNormally,
      );
    });

    test('the timeout is long enough for a slow mobile network and is bounded',
        () {
      // Always-on VPS: 15s is enough for a village 4G stall and short enough
      // that a spinner is not an eternity. Override via API_TIMEOUT_SECONDS.
      for (final env in AppEnvironment.values) {
        expect(env.timeout.inSeconds, greaterThanOrEqualTo(15));
        expect(env.timeout.inSeconds, lessThanOrEqualTo(60));
      }
    });
  });
}
