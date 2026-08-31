import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:print_bluetooth_thermal/print_bluetooth_thermal.dart';

import '../../../core/printing/printer_providers.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/util/haptic_widgets.dart';

/// One-time setup for the receipt printer that auto-prints every new order
/// (see main.dart's NEW_ORDER push handling, and printer_service.dart).
/// Only ever lists devices already paired in Android's own Bluetooth
/// settings - this screen doesn't pair a NEW device itself (that still
/// needs the phone's system Bluetooth settings, same as any other Bluetooth
/// accessory), it just picks which already-paired device to use.
class AdminPrinterSettingsScreen extends ConsumerStatefulWidget {
  const AdminPrinterSettingsScreen({super.key});

  @override
  ConsumerState<AdminPrinterSettingsScreen> createState() => _AdminPrinterSettingsScreenState();
}

class _AdminPrinterSettingsScreenState extends ConsumerState<AdminPrinterSettingsScreen> {
  List<BluetoothInfo>? _pairedDevices;
  bool _isLoadingDevices = false;
  bool _isTesting = false;
  String? _statusMessage;

  Future<void> _loadPairedDevices() async {
    setState(() {
      _isLoadingDevices = true;
      _statusMessage = null;
    });

    final printerService = ref.read(printerServiceProvider);

    try {
      if (!await printerService.hasPermission()) {
        final granted = await printerService.requestPermission();
        if (!granted) {
          if (!mounted) return;
          setState(() {
            _statusMessage = 'Bluetooth permission is needed to see paired printers. '
                'Grant it from Android Settings > Apps > GP-Store > Permissions.';
          });
          return;
        }
      }

      if (!await printerService.isBluetoothOn()) {
        if (!mounted) return;
        setState(() => _statusMessage = 'Turn on Bluetooth, then try again.');
        return;
      }

      final devices = await printerService.pairedPrinters();
      if (!mounted) return;
      setState(() => _pairedDevices = devices);

      if (devices.isEmpty) {
        setState(() {
          _statusMessage = 'No paired Bluetooth devices found. Pair your printer first in '
              "Android's own Bluetooth settings, then come back here.";
        });
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _statusMessage = 'Could not read paired devices: $e');
    } finally {
      if (mounted) setState(() => _isLoadingDevices = false);
    }
  }

  Future<void> _selectPrinter(BluetoothInfo device) async {
    setState(() => _statusMessage = null);

    final connected = await ref.read(printerServiceProvider).connectAndRemember(device);
    if (!mounted) return;

    if (connected) {
      ref.invalidate(savedPrinterNameProvider);
      setState(() => _statusMessage = 'Connected to ${device.name}. Try a test print below.');
    } else {
      setState(() => _statusMessage = 'Could not connect to ${device.name} - make sure it\'s powered on and in range.');
    }
  }

  Future<void> _forgetPrinter() async {
    await ref.read(printerServiceProvider).forget();
    ref.invalidate(savedPrinterNameProvider);
    if (!mounted) return;
    setState(() {
      _pairedDevices = null;
      _statusMessage = null;
    });
  }

  Future<void> _testPrint() async {
    setState(() {
      _isTesting = true;
      _statusMessage = null;
    });

    final ok = await ref.read(printerServiceProvider).printTestPage();
    if (!mounted) return;

    setState(() {
      _isTesting = false;
      _statusMessage = ok ? 'Test page sent - check your printer.' : 'Test print failed - check the printer is on and connected.';
    });
  }

  @override
  Widget build(BuildContext context) {
    final savedName = ref.watch(savedPrinterNameProvider).valueOrNull;

    return Scaffold(
      appBar: AppBar(title: const Text('Receipt Printer')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('New orders auto-print', style: TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                Text(
                  savedName != null
                      ? 'Connected to: $savedName'
                      : 'No printer set up yet. Every new order will auto-print here once one is connected.',
                  style: const TextStyle(color: AdminColors.textSecondary, fontSize: 13),
                ),
                const SizedBox(height: 4),
                const Text(
                  'Only works while this app is open on this device - it cannot print while fully closed.',
                  style: TextStyle(color: AdminColors.textSecondary, fontSize: 12, fontStyle: FontStyle.italic),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          if (savedName != null) ...[
            FilledButton.icon(
              onPressed: _isTesting ? null : _testPrint,
              icon: _isTesting
                  ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : const Icon(Icons.print_outlined),
              label: const Text('Test Print'),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: hapticize(_forgetPrinter),
              icon: const Icon(Icons.link_off, color: AdminColors.danger),
              label: const Text('Forget This Printer', style: TextStyle(color: AdminColors.danger)),
            ),
            const SizedBox(height: 20),
          ],
          Text(savedName != null ? 'Switch to a different printer' : 'Connect a printer', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          const Text(
            "Pair your Bluetooth printer in Android's Bluetooth settings first if you haven't already, then tap below.",
            style: TextStyle(color: AdminColors.textSecondary, fontSize: 13),
          ),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: _isLoadingDevices ? null : _loadPairedDevices,
            icon: _isLoadingDevices
                ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                : const Icon(Icons.bluetooth_searching),
            label: const Text('Show Paired Devices'),
          ),
          if (_statusMessage != null) ...[
            const SizedBox(height: 12),
            Text(_statusMessage!, style: const TextStyle(color: AdminColors.textSecondary)),
          ],
          if (_pairedDevices != null && _pairedDevices!.isNotEmpty) ...[
            const SizedBox(height: 12),
            ..._pairedDevices!.map(
              (device) => Container(
                margin: const EdgeInsets.only(bottom: 8),
                decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
                child: ListTile(
                  leading: const Icon(Icons.print_outlined, color: AdminColors.primary),
                  title: Text(device.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text(device.macAdress),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: hapticize(() => _selectPrinter(device)),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
