import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Remembers which paired Bluetooth printer to use, so the store owner only
/// has to pick it once (in AdminPrinterSettingsScreen) - every later
/// auto-print just reconnects to this saved MAC address. Reuses
/// FlutterSecureStorage rather than pulling in a separate
/// shared_preferences dependency just for two small strings - nothing here
/// is sensitive, secure storage is just the storage mechanism this app
/// already depends on.
class PrinterSettingsStorage {
  PrinterSettingsStorage()
      : _storage = const FlutterSecureStorage(
          aOptions: AndroidOptions(resetOnError: true, encryptedSharedPreferences: true),
        );

  final FlutterSecureStorage _storage;

  static const _macKey = 'printer_mac_address';
  static const _nameKey = 'printer_name';

  Future<void> savePrinter({required String macAddress, required String name}) async {
    await _storage.write(key: _macKey, value: macAddress);
    await _storage.write(key: _nameKey, value: name);
  }

  Future<String?> getSavedMacAddress() => _storage.read(key: _macKey);

  Future<String?> getSavedPrinterName() => _storage.read(key: _nameKey);

  Future<void> clear() async {
    await _storage.delete(key: _macKey);
    await _storage.delete(key: _nameKey);
  }
}
