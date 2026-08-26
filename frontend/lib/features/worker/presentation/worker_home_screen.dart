import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/worker_location_service.dart';
import '../data/worker_repository.dart';
import '../domain/worker_models.dart';
import 'worker_order_screen.dart';
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
  int _pending = 0;
  bool _refreshing = false;

  late final WorkerLocationService _location =
      WorkerLocationService(repository: widget.repository);

  /// Why location is not being shared, when it is not. Shown once, in place.
  String? _locationProblem;

  @override
  void initState() {
    super.initState();
    _profile = widget.initialProfile;
    _syncLocationTracking();
    _refresh();
  }

  @override
  void dispose() {
    // The GPS stream stops with the screen. There is no background service
    // and no foreground notification, which is the point: when this app is
    // not open, this app is not tracking anybody.
    _location.stop();
    super.dispose();
  }

  /// Starts or stops GPS to match whether there is anything to track.
  ///
  /// THE CONDITION IS THE FEATURE. Location runs only while the worker has a
  /// live delivery - not while they are logged in, not while they are on
  /// shift, not while the app is merely open. A worker restocking a shelf
  /// with no delivery out is not a worker whose position the shop needs, and
  /// GPS is the most expensive thing this app could ask of a cheap phone's
  /// battery.
  Future<void> _syncLocationTracking() async {
    final shouldTrack = _profile.activeTasks.isNotEmpty;

    if (shouldTrack && !_location.isRunning) {
      final problem = await _location.start();
      if (mounted) setState(() => _locationProblem = problem);
    } else if (!shouldTrack && _location.isRunning) {
      await _location.stop();
      if (mounted) setState(() => _locationProblem = null);
    }
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

      // ONE NETWORK CALL. /api/worker/me carries the profile AND the active
      // tasks, because the home screen cannot draw without both and two
      // requests would only mean the slower one arriving later. The pending
      // count is local storage, not a request.
      final profile = await widget.repository.me();
      final pending = await widget.repository.pendingScans();
      if (!mounted) return;
      setState(() {
        _profile = profile;
        _pending = pending.length;
      });
      await _syncLocationTracking();
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
    if (outcome == null) {
      return;
    }

    // Straight into the order. The scan response already carries it, so this
    // costs no request at all - which is what makes SCAN -> SHOW ORDER feel
    // like one action rather than two.
    final order = outcome.order;
    if (order != null && mounted) {
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) =>
              WorkerOrderScreen(repository: widget.repository, order: order),
        ),
      );
    }

    await _refresh();
  }

  Future<void> _openTask(WorkerTask task) async {
    try {
      final order = await widget.repository.order(task.orderId);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) =>
              WorkerOrderScreen(repository: widget.repository, order: order),
        ),
      );
      await _refresh();
    } catch (_) {
      if (mounted) _tell('Could not open that order. Check your connection.');
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
                style: theme.textTheme.titleLarge
                    ?.copyWith(color: theme.colorScheme.primary),
              ),
              const SizedBox(height: 20),

              // Territory. When there is none, say so - a blank would read as
              // a loading failure, and "no territory yet" is a real, fixable
              // state that an administrator needs to hear about.
              if (territory != null)
                _Facts(rows: {
                  'Zone': _profile.zoneCode ?? '-',
                  'Subzone': territory,
                  if (_profile.subzoneName != null)
                    'Area': _profile.subzoneName!,
                })
              else
                _Notice(
                  icon: Icons.map_outlined,
                  text:
                      'No territory assigned yet. You can still scan orders an '
                      'administrator assigns to you.',
                ),

              const SizedBox(height: 20),
              Row(
                children: [
                  Expanded(
                    child: _Stat(
                        label: "Today's orders",
                        value: '${_profile.todaysOrders}'),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                      child: _Stat(
                          label: 'Status',
                          value: _statusLabel(_profile.status))),
                ],
              ),

              if (_pending > 0) ...[
                const SizedBox(height: 16),
                _Notice(
                  icon: Icons.cloud_off,
                  text:
                      '$_pending scan${_pending == 1 ? '' : 's'} waiting to be sent. '
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
                    style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 1),
                  ),
                  style: FilledButton.styleFrom(
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16)),
                  ),
                ),
              ),

              if (_locationProblem != null) ...[
                const SizedBox(height: 16),
                _Notice(
                    icon: Icons.location_off,
                    text: _locationProblem!,
                    emphasis: true),
              ],

              if (_profile.activeTasks.isNotEmpty) ...[
                const SizedBox(height: 32),
                Text('Active orders: ${_profile.activeTasks.length}',
                    style: theme.textTheme.titleLarge),
                const SizedBox(height: 8),
                for (final task in _profile.activeTasks)
                  _TaskTile(task: task, onOpen: () => _openTask(task)),
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
                  Text(entry.key,
                      style: const TextStyle(color: Colors.white70)),
                  Text(entry.value,
                      style: const TextStyle(
                          fontWeight: FontWeight.w700, fontSize: 17)),
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
              style:
                  const TextStyle(fontSize: 28, fontWeight: FontWeight.w800)),
          const SizedBox(height: 2),
          Text(label,
              style: const TextStyle(color: Colors.white70, fontSize: 13)),
        ],
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice(
      {required this.icon, required this.text, this.emphasis = false});

  final IconData icon;
  final String text;
  final bool emphasis;

  @override
  Widget build(BuildContext context) {
    final colour =
        emphasis ? Theme.of(context).colorScheme.tertiary : Colors.white70;
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

class _TaskTile extends StatelessWidget {
  const _TaskTile({required this.task, required this.onOpen});

  final WorkerTask task;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(task.orderNumber, style: theme.textTheme.titleLarge),
                Text(task.deliveryStatus,
                    style: theme.textTheme.bodyMedium
                        ?.copyWith(color: theme.colorScheme.outline)),
              ],
            ),
          ),
          OutlinedButton(onPressed: onOpen, child: const Text('Open')),
        ],
      ),
    );
  }
}
