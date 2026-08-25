import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/config/app_environment.dart';

void main() {
  group('AppEnvironment', () {
    test('production and staging point at a real https host, never localhost', () {
      // The mistake this catches is a shipped APK talking to 10.0.2.2, which
      // fails on every real phone while working perfectly on the emulator the
      // developer tested on.
      for (final env in [AppEnvironment.production, AppEnvironment.staging]) {
        expect(env.baseUrl, startsWith('https://'), reason: '$env must be encrypted');
        expect(env.baseUrl, isNot(contains('localhost')));
        expect(env.baseUrl, isNot(contains('10.0.2.2')));
        expect(env.baseUrl, isNot(contains('onrender')));
        expect(env.baseUrl, isNot(contains('render.com')));
        expect(env.baseUrl, isNot(contains('railway')));
        expect(env.baseUrl, AppEnvironment.productionApiBaseUrl);
        expect(env.baseUrl, 'https://api.gpstore.co.in/v1');
      }
    });

    test('every environment targets the versioned API path', () {
      // The backend serves under context-path /v1; a base URL without it
      // 404s every single request.
      for (final env in AppEnvironment.values) {
        expect(env.baseUrl, endsWith('/v1'), reason: '$env is missing the API version');
      }
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

    test('the timeout is long enough for a slow mobile network and is bounded', () {
      // Always-on VPS: 15s is enough for a village 4G stall and short enough
      // that a spinner is not an eternity. Override via API_TIMEOUT_SECONDS.
      for (final env in AppEnvironment.values) {
        expect(env.timeout.inSeconds, greaterThanOrEqualTo(15));
        expect(env.timeout.inSeconds, lessThanOrEqualTo(60));
      }
    });
  });
}
