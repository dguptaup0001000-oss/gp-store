import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// The three storage operations the voice feature needs, behind an interface.
///
/// Exists so the dedup log and the on/off setting can be tested for the
/// behaviour that actually matters - "is the same order ever announced
/// twice", "does a missing key mean on" - on a plain in-memory map, with no
/// device, no Android keystore and no platform channel. Testing those against
/// FlutterSecureStorage directly is not possible in a unit test, and testing
/// only the string formatting (which is all the earlier tests could reach)
/// would leave the duplicate-suppression rule unverified.
abstract class KeyValueStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// Production implementation: flutter_secure_storage, already a dependency of
/// this app, so this adds no new package.
class SecureKeyValueStore implements KeyValueStore {
  const SecureKeyValueStore({FlutterSecureStorage storage = const FlutterSecureStorage()})
      : _storage = storage;

  final FlutterSecureStorage _storage;

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) => _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}
