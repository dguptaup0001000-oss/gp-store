import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/storage/token_storage.dart';
import 'package:gpstore/features/auth/data/auth_repository.dart';

import '../../../support/test_api_client.dart';

/// A secure-storage stub that starts with a real session in it and records
/// what gets deleted, so "was the account actually signed out on this
/// device" is checkable rather than assumed.
class _FakeSecureStorage {
  final Map<String, String> values = {
    'access_token': 'access-abc',
    'refresh_token': 'refresh-xyz',
    'remember_me': 'true',
  };

  void install() {
    TestWidgetsFlutterBinding.ensureInitialized();
    const channel = MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async {
        final args = (call.arguments as Map?)?.cast<String, dynamic>() ?? const {};
        switch (call.method) {
          case 'read':
            return values[args['key'] as String];
          case 'readAll':
            return Map<String, String>.from(values);
          case 'write':
            values[args['key'] as String] = args['value'] as String;
            return null;
          case 'delete':
            values.remove(args['key'] as String);
            return null;
          case 'deleteAll':
            values.clear();
            return null;
        }
        return null;
      },
    );
  }
}

void main() {
  late _FakeSecureStorage storage;
  late FakeHttpClientAdapter adapter;
  late List<String> requests;

  /// Builds a repository whose every call is recorded in [requests].
  AuthRepository buildRepository({bool fcmDeleteFails = false}) {
    adapter = FakeHttpClientAdapter();

    adapter.on('DELETE', '/api/customers/me/fcm-token', (options) {
      requests.add('DELETE fcm-token');
      if (fcmDeleteFails) return const FakeResponse({'error': 'nope'}, statusCode: 500);
      return const FakeResponse(null);
    });
    adapter.on('POST', '/api/auth/logout', (options) {
      requests.add('POST logout');
      return const FakeResponse(null);
    });
    adapter.on('POST', '/api/auth/logout-all', (options) {
      requests.add('POST logout-all');
      return const FakeResponse(null);
    });

    final tokenStorage = TokenStorage();
    final client = ApiClient(tokenStorage: tokenStorage);
    client.dio.httpClientAdapter = adapter;
    return AuthRepository(apiClient: client, tokenStorage: tokenStorage);
  }

  setUp(() {
    requests = [];
    storage = _FakeSecureStorage()..install();
  });

  group('what logout does about this device push token', () {
    test('logout releases the device token from the account', () async {
      // A device token identifies a phone, not a person. Left attached, the
      // signed-out account keeps receiving order pushes - carrying a
      // customer name and an amount - on a phone somebody else may now be
      // signed in on.
      await buildRepository().logout();

      expect(requests, contains('DELETE fcm-token'));
    });

    test('the token is released BEFORE the session is ended', () async {
      // The endpoint is authenticated. Reversed, the call would go out with
      // a session the server has just revoked, silently 401, and leave the
      // token attached - the exact bug this is here to prevent, in a form
      // that looks like it works.
      await buildRepository().logout();

      expect(requests.indexOf('DELETE fcm-token'), lessThan(requests.indexOf('POST logout')));
    });

    test('logging out of every device releases it too', () async {
      await buildRepository().logoutAllDevices();

      expect(requests, contains('DELETE fcm-token'));
      expect(requests.indexOf('DELETE fcm-token'), lessThan(requests.indexOf('POST logout-all')));
    });

    test('a failed release still signs the customer out locally', () async {
      // Being unable to reach the server must never leave someone stuck
      // signed in on their own phone.
      await buildRepository(fcmDeleteFails: true).logout();

      expect(storage.values, isEmpty, reason: 'the session must be cleared regardless');
    });

    test('a successful logout clears every stored credential', () async {
      await buildRepository().logout();

      expect(storage.values, isEmpty);
    });
  });
}
