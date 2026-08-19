import 'package:esc_pos_utils_plus/esc_pos_utils_plus.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:print_bluetooth_thermal/print_bluetooth_thermal.dart';

import '../../features/orders/domain/order_models.dart';
import 'printer_settings_storage.dart';

/// Talks to a cheap 58mm Bluetooth (Classic/SPP) ESC/POS thermal printer -
/// the common receipt-printer type sold for small shops. Only ever connects
/// to a device the store owner already paired themselves in Android's own
/// Bluetooth settings (see AdminPrinterSettingsScreen) - this never scans
/// for new devices, which is what keeps the Android permission surface down
/// to just BLUETOOTH_CONNECT (no location permission needed).
class PrinterService {
  PrinterService({required this.settingsStorage});

  final PrinterSettingsStorage settingsStorage;

  /// print_bluetooth_thermal only CHECKS permission - it never triggers the
  /// OS prompt itself, that's what requestPermission() below is for.
  Future<bool> hasPermission() => PrintBluetoothThermal.isPermissionBluetoothGranted;

  /// Requests once. Call this only right before the store owner picks a
  /// printer in AdminPrinterSettingsScreen - after that, a saved printer
  /// means auto-print never needs to ask again (the OS itself won't
  /// re-prompt once granted).
  Future<bool> requestPermission() async {
    final statuses = await [Permission.bluetoothConnect, Permission.bluetoothScan].request();
    return statuses.values.every((status) => status.isGranted);
  }

  Future<bool> isBluetoothOn() => PrintBluetoothThermal.bluetoothEnabled;

  /// Devices already paired in Android's Bluetooth settings - not a live
  /// scan, just what the OS already knows about.
  Future<List<BluetoothInfo>> pairedPrinters() => PrintBluetoothThermal.pairedBluetooths;

  Future<bool> connectAndRemember(BluetoothInfo device) async {
    final connected = await PrintBluetoothThermal.connect(macPrinterAddress: device.macAdress);
    if (connected) {
      await settingsStorage.savePrinter(macAddress: device.macAdress, name: device.name);
    }
    return connected;
  }

  Future<void> forget() async {
    try {
      await PrintBluetoothThermal.disconnect;
    } catch (_) {
      // Not connected, or the printer's already gone - either way there's
      // nothing left to disconnect from.
    }
    await settingsStorage.clear();
  }

  /// Reconnects to the saved printer if the connection dropped (Bluetooth
  /// links to cheap printers do this) - returns false if no printer has
  /// ever been set up, so callers can tell "not configured" apart from
  /// "configured but unreachable right now".
  Future<bool> _ensureConnected() async {
    if (await PrintBluetoothThermal.connectionStatus) return true;

    final mac = await settingsStorage.getSavedMacAddress();
    if (mac == null) return false;

    return PrintBluetoothThermal.connect(macPrinterAddress: mac);
  }

  Future<bool> printTestPage() async {
    if (!await _ensureConnected()) return false;

    final profile = await CapabilityProfile.load();
    final generator = Generator(PaperSize.mm58, profile);
    List<int> bytes = [];

    bytes += generator.text(
      'GP-Store',
      styles: const PosStyles(align: PosAlign.center, bold: true, height: PosTextSize.size2, width: PosTextSize.size2),
    );
    bytes += generator.text('Test Print', styles: const PosStyles(align: PosAlign.center));
    bytes += generator.hr();
    bytes += generator.text('If you can read this, your');
    bytes += generator.text('printer is set up correctly.');
    bytes += generator.feed(2);
    bytes += generator.cut();

    return PrintBluetoothThermal.writeBytes(bytes);
  }

  /// The actual auto-print, called the moment a NEW_ORDER push arrives
  /// (see main.dart). Returns false (never throws) if no printer is
  /// configured or it's unreachable - printing is a convenience on top of
  /// the order, never something that should crash the app or block
  /// anything else from working.
  Future<bool> printOrderReceipt(OrderDetail order) async {
    try {
      if (!await _ensureConnected()) return false;

      final profile = await CapabilityProfile.load();
      final generator = Generator(PaperSize.mm58, profile);
      List<int> bytes = [];

      bytes += generator.text(
        'GP-Store',
        styles: const PosStyles(align: PosAlign.center, bold: true, height: PosTextSize.size2, width: PosTextSize.size2),
      );
      bytes += generator.text('NEW ORDER', styles: const PosStyles(align: PosAlign.center, bold: true));
      bytes += generator.hr();

      bytes += generator.text(order.orderNumber, styles: const PosStyles(bold: true));
      bytes += generator.text(order.orderDate);
      bytes += generator.text('Payment: ${_paymentLabel(order.paymentStatus)}');
      bytes += generator.hr();

      final address = order.address;
      if (address != null) {
        bytes += generator.text(address.fullName, styles: const PosStyles(bold: true));
        bytes += generator.text(address.fullAddress);
        bytes += generator.text('Ph: ${address.mobileNumber}');
        bytes += generator.hr();
      }

      for (final item in order.items) {
        final name = item.productName ?? 'Item';
        final label = '${item.quantity}x $name';
        bytes += generator.text(_twoColumnLine(label, '₹${item.totalPrice.toStringAsFixed(0)}'));
      }
      bytes += generator.hr();

      final discount = order.discountAmount ?? 0;
      if (discount > 0) {
        bytes += generator.text(_twoColumnLine('Discount', '-₹${discount.toStringAsFixed(0)}'));
      }
      final deliveryFee = order.deliveryFee ?? 0;
      if (deliveryFee > 0) {
        bytes += generator.text(_twoColumnLine('Delivery Fee', '₹${deliveryFee.toStringAsFixed(0)}'));
      }
      bytes += generator.text(
        _twoColumnLine('TOTAL', '₹${order.totalAmount.toStringAsFixed(0)}'),
        styles: const PosStyles(bold: true, height: PosTextSize.size2, width: PosTextSize.size2),
      );

      bytes += generator.feed(2);
      bytes += generator.cut();

      return await PrintBluetoothThermal.writeBytes(bytes);
    } catch (_) {
      // Printer off, out of range, out of paper, whatever - the order
      // already exists regardless of whether the receipt printed.
      return false;
    }
  }

  String _paymentLabel(String paymentStatus) {
    switch (paymentStatus) {
      case 'COD_PENDING':
        return 'Cash on Delivery';
      case 'PAID':
        return 'Paid (UPI)';
      case 'PENDING':
        return 'UPI - awaiting payment';
      default:
        return paymentStatus;
    }
  }

  /// Left-aligns [left] and right-aligns [right] within a fixed character
  /// width - 32 is the standard character count per line for a 58mm
  /// printer's font A at normal size. Falls back to just separating the two
  /// with a single space if they're too long to fit side by side, rather
  /// than truncating either one.
  String _twoColumnLine(String left, String right, {int width = 32}) {
    final space = width - left.length - right.length;
    if (space <= 0) return '$left $right';
    return left + (' ' * space) + right;
  }
}
