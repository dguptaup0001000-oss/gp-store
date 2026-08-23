import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/worker_repository.dart';
import '../domain/worker_models.dart';
import 'worker_scan_screen.dart';

/// Name, code, territory, today's count, and one very large button.
///
/// The brief asked for exactly this and nothing else, and the restraint is the
/// feature. A worker opens this holding a carton; every widget that is not the
/// scan button is something between them and the only thing they came here to
/// do. There is no list of assigned work, no map, no chat, no statistics.
class WorkerHomeScreen extends StatefulWidget {
  const WorkerHomeScreen({
    super.key,
    required this.repository,
    required this.initialProfile,
    required this.onSignOut,
  });

  final WorkerRepository repository;
  final WorkerProfile initialProfile;
  final Future<void> Function() onSignOut;

  @override
  State<WorkerHomeScreen> createState() => _WorkerHomeScreenState();
}

class _WorkerHomeScreenState extends State<WorkerHomeScreen> {
  late WorkerProfile _profile;
  List<WorkerScanRow> _today = const [];
  int _pending = 0;
  bool _refreshing = false;

  @override
  void initState() {
    super.initState();
    _profile = widget.initialProfile;
    _refresh();
  }

  /// Refreshes the screen and, first, tries to send anything stuck on the phone.
  ///
  /// Flushing here rather than on a timer is deliberate: opening the app or
  /// pulling to refresh is the moment a worker has signal and attention, and a
  /// background retry loop on a cheap phone is battery spent on guessing.
  Future<void> _refresh() async {
    if (_refreshing) return;
    setState(() => _refreshing = true);
    try {
      final flushed = await widget.repository.flushQueue();
      if (flushed.isNotEmpty && mounted) {
        final sent = flushed.where((r) => r.accepted).length;
        _tell(sent > 0
            ? '$sent queued scan${sent == 1 ? '' : 's'} submitted.'
            : 'Queued scans reached the server but were refused.');
      }

      final profile = await widget.repository.me();
      final today = await widget.repository.myOrders();
      final pending = await widget.repository.pendingScans();
      if (!mounted) return;
      setState(() {
        _profile = profile;
        _today = today;
        _pending = pending.length;
      });
    } catch (_) {
      // Offline. The screen keeps showing what it last knew rather than
      // blanking - stale numbers beat an error page when the next action is
      // to scan, which works offline anyway.
      final pending = await widget.repository.pendingScans();
      if (mounted) setState(() => _pending = pending.length);
    } finally {
      if (mounted) setState(() => _refreshing = false);
    }
  }

  void _tell(String message) {
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _openScanner() async {
    HapticFeedback.selectionClick();
    final outcome = await Navigator.of(context).push<ScanOutcome>(
      MaterialPageRoute(
        builder: (_) => WorkerScanScreen(repository: widget.repository),
      ),
    );
    if (outcome != null) {
      await _refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final territory = _profile.subzoneCode;

    return Scaffold(
      appBar: AppBar(
        title: const Text('GP-Store Worker'),
        actions: [
          IconButton(
            tooltip: 'Sign out',
            onPressed: () async => widget.onSignOut(),
            icon: const Icon(Icons.logout),
          ),
        ],
      ),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
            children: [
              Text(_profile.name, style: theme.textTheme.headlineLarge),
              const SizedBox(height: 2),
              Text(
                _profile.workerCode,
                style: theme.textTheme.titleLarge?.copyWith(color: theme.colorScheme.primary),
              ),
              const SizedBox(height: 20),

              // Territory. When there is none, say so - a blank would read as
              // a loading failure, and "no territory yet" is a real, fixable
              // state that an administrator needs to hear about.
              if (territory != null)
                _Facts(rows: {
                  'Zone': _profile.zoneCode ?? '-',
                  'Subzone': territory,
                  if (_profile.subzoneName != null) 'Area': _profile.subzoneName!,
                })
              else
                _Notice(
                  icon: Icons.map_outlined,
                  text: 'No territory assigned yet. You can still scan orders an '
                      'administrator assigns to you.',
                ),

              const SizedBox(height: 20),
              Row(
                children: [
                  Expanded(
                    child: _Stat(label: "Today's orders", value: '${_profile.todaysOrders}'),
                  ),
                  const SizedBox(width: 12),
                  Expanded(child: _Stat(label: 'Status', value: _statusLabel(_profile.status))),
                ],
              ),

              if (_pending > 0) ...[
                const SizedBox(height: 16),
                _Notice(
                  icon: Icons.cloud_off,
                  text: '$_pending scan${_pending == 1 ? '' : 's'} waiting to be sent. '
                      'Pull down to retry when you have signal.',
                  emphasis: true,
                ),
              ],

              const SizedBox(height: 28),

              // The button the whole app exists for. Deliberately enormous:
              // thumb-reachable one-handed, and impossible to miss in a hurry.
              SizedBox(
                height: 120,
                child: FilledButton.icon(
                  onPressed: _openScanner,
                  icon: const Icon(Icons.qr_code_scanner, size: 42),
                  label: const Text(
                    'SCAN ORDER QR',
                    style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, letterSpacing: 1),
                  ),
                  style: FilledButton.styleFrom(
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                  ),
                ),
              ),

              if (_today.isNotEmpty) ...[
                const SizedBox(height: 32),
                Text('Today', style: theme.textTheme.titleLarge),
                const SizedBox(height: 8),
                for (final row in _today) _ScanRowTile(row: row),
              ],
            ],
          ),
        ),
      ),
    );
  }

  static String _statusLabel(String status) {
    switch (status) {
      case 'AVAILABLE':
        return 'Available';
      case 'ON_DELIVERY':
        return 'On delivery';
      default:
        return 'Off duty';
    }
  }
}

class _Facts extends StatelessWidget {
  const _Facts({required this.rows});

  final Map<String, String> rows;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.05),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        children: [
          for (final entry in rows.entries)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(entry.key, style: const TextStyle(color: Colors.white70)),
                  Text(entry.value,
                      style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 17)),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.05),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        children: [
          Text(value,
              style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800)),
          const SizedBox(height: 2),
          Text(label, style: const TextStyle(color: Colors.white70, fontSize: 13)),
        ],
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({required this.icon, required this.text, this.emphasis = false});

  final IconData icon;
  final String text;
  final bool emphasis;

  @override
  Widget build(BuildContext context) {
    final colour = emphasis ? Theme.of(context).colorScheme.tertiary : Colors.white70;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.05),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: colour),
          const SizedBox(width: 12),
          Expanded(child: Text(text, style: TextStyle(color: colour))),
        ],
      ),
    );
  }
}

class _ScanRowTile extends StatelessWidget {
  const _ScanRowTile({required this.row});

  final WorkerScanRow row;

  @override
  Widget build(BuildContext context) {
    // Refused scans are shown, not hidden. A worker who was told no needs to
    // be able to look back at why, and hiding them would make the list a
    // flattering summary rather than a record.
    final ok = row.accepted;
    return ListTile(
      contentPadding: EdgeInsets.zero,
      dense: true,
      leading: Icon(
        ok ? Icons.check_circle : Icons.cancel,
        color: ok ? Theme.of(context).colorScheme.primary : Theme.of(context).colorScheme.error,
      ),
      title: Text(row.orderNumber ?? 'Unrecognised code',
          style: const TextStyle(fontWeight: FontWeight.w600)),
      subtitle: Text(
        [
          if (row.subzoneCode != null) row.subzoneCode!,
          if (row.scannedAt != null) _time(row.scannedAt!),
          if (!ok && row.reason != null) row.reason!,
        ].join('  ·  '),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
    );
  }

  static String _time(DateTime t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}:'
      '${t.second.toString().padLeft(2, '0')}';
}
