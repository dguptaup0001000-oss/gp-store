import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'printer_service.dart';
import 'printer_settings_storage.dart';

final printerSettingsStorageProvider = Provider<PrinterSettingsStorage>((ref) {
  return PrinterSettingsStorage();
});

final printerServiceProvider = Provider<PrinterService>((ref) {
  return PrinterService(settingsStorage: ref.watch(printerSettingsStorageProvider));
});

/// Whether a printer has been paired and saved yet - drives whether
/// AdminPrinterSettingsScreen shows "connect a printer" or "connected to X".
final savedPrinterNameProvider = FutureProvider<String?>((ref) {
  return ref.watch(printerSettingsStorageProvider).getSavedPrinterName();
});
