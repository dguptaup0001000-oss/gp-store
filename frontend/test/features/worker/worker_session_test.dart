import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/storage/token_storage.dart';

/// Worker login must not persist tokens until /api/worker/me succeeds.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Map<String, String> disk;

  setUp(() {
    disk = {};
    const channel = MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async {
        final args = (call.arguments as Map?)?.cast<String, dynamic>() ?? const {};
        switch (call.method) {
          case 'read':
            return disk[args['key'] as String];
          case 'write':
            disk[args['key'] as String] = args['value'] as String;
            return null;
          case 'delete':
            disk.remove(args['key'] as String);
            return null;
          case 'deleteAll':
            disk.clear();
            return null;
        }
        return null;
      },
    );
  });

  tearDown(() {
    const channel = MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('holdTokensInMemory does not write to disk', () async {
    final storage = TokenStorage(keyPrefix: 'worker_');
    storage.holdTokensInMemory(accessToken: 'access', refreshToken: 'refresh');

    expect(await storage.getAccessToken(), 'access');
    expect(await storage.getRefreshToken(), 'refresh');
    expect(disk, isEmpty);
  });

  test('verification failure clears memory so a later request has no token', () async {
    final storage = TokenStorage(keyPrefix: 'worker_');
    storage.holdTokensInMemory(accessToken: 'access', refreshToken: 'refresh');
    await storage.clear();

    expect(await storage.getAccessToken(), isNull);
    expect(await storage.getRefreshToken(), isNull);
    expect(disk, isEmpty);
  });

  test('saveTokens after a successful me() persists worker keys, not customer keys', () async {
    final storage = TokenStorage(keyPrefix: 'worker_');
    storage.holdTokensInMemory(accessToken: 'access', refreshToken: 'refresh');
    await storage.saveTokens(accessToken: 'access', refreshToken: 'refresh');

    expect(disk['worker_access_token'], 'access');
    expect(disk['worker_refresh_token'], 'refresh');
    expect(disk.containsKey('access_token'), isFalse);
  });
}
