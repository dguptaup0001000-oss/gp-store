import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/store/store_status.dart';
import '../../core/util/haptic_widgets.dart';
import '../../features/auth/presentation/auth_providers.dart';
import '../design/admin_components.dart';
import '../design/admin_format.dart';
import '../design/admin_tokens.dart';
import 'store_operations_models.dart';
import 'store_operations_providers.dart';

/// Store hours: what the shop is doing now, and the two levers that change it.
///
/// A CARD OF CONTROLS, NOT A LIST OF GIANT CARDS. Everything an operator needs
/// to decide "should I pause orders?" is on one screen without scrolling: what
/// customers are being told right now, the three-way switch, and the days
/// already marked closed. A screen that makes you scroll to see the state you
/// are about to change is a screen that gets changed by mistake.
class StoreOperationsScreen extends ConsumerWidget {
  const StoreOperationsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final operations = ref.watch(storeOperationsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Store Hours')),
      body: operations.when(
        loading: () => const _OperationsSkeleton(),
        error: (error, _) => AdminErrorState(
          message: "Couldn't load the store controls: ${extractErrorMessage(error)}",
          onRetry: hapticize(() => ref.invalidate(storeOperationsProvider)),
        ),
        data: (data) => RefreshIndicator(
          color: AdminColors.primary,
          onRefresh: () async => ref.invalidate(storeOperationsProvider),
          child: ListView(
            padding: const EdgeInsets.all(AdminSpacing.lg),
            children: [
              _LiveStatusCard(operations: data),
              const SizedBox(height: AdminSpacing.lg),
              _AcceptanceCard(operations: data),
              const SizedBox(height: AdminSpacing.lg),
              _ClosuresCard(operations: data),
              const SizedBox(height: AdminSpacing.xxl),
            ],
          ),
        ),
      ),
    );
  }
}

/// What the customer app is being told, right now.
///
/// SHOWN FIRST, and shown as the customer's own words where possible. An
/// operator who cannot see what the shop is currently saying is deciding
/// blind - this is the difference between "pause orders" and "pause orders
/// again, because I could not tell it was already paused".
class _LiveStatusCard extends StatelessWidget {
  const _LiveStatusCard({required this.operations});

  final StoreOperations operations;

  @override
  Widget build(BuildContext context) {
    final status = operations.status;
    final open = status.acceptingOrders;

    return AdminSectionCard(
      title: 'Right now',
      trailing: AdminStatusBadge(
        label: open ? 'Taking orders' : 'Orders paused',
        tone: open ? AdminStatusTone.success : AdminStatusTone.danger,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          // Browsing is always open, and the console says so out loud. It is
          // the one guarantee the whole feature rests on, and an operator
          // pausing orders needs to know the catalogue stays up.
          const _Fact(
            icon: Icons.storefront_outlined,
            label: 'Browsing',
            value: 'Open 24 hours',
            tone: AdminStatusTone.success,
          ),
          _Fact(
            icon: status.mode == StoreMode.night
                ? Icons.nightlight_round
                : Icons.wb_sunny_outlined,
            label: 'Delivery window',
            value: status.mode == StoreMode.night
                ? 'Closed - next run ${_windowText(status)}'
                : 'Running until ${_time(status.deliveryEndTime)}',
          ),
          if (status.deliveryDate != null)
            _Fact(
              icon: Icons.event_outlined,
              label: 'An order placed now',
              value: 'Arrives ${AdminFormat.relativeDay(status.deliveryDate!)}'
                  '${status.deliveryType == StoreDeliveryType.nextMorning ? ' from ${_time(status.deliveryStartTime)}' : ''}',
            ),
          if (status.countdownActive)
            _Fact(
              icon: Icons.timer_outlined,
              label: 'Same-day ordering',
              value: 'Closes in ${((status.countdownSeconds ?? 0) / 60).ceil()} min',
              tone: AdminStatusTone.warning,
            ),
          if (status.closedToday)
            const _Fact(
              icon: Icons.event_busy_outlined,
              label: 'Today',
              value: 'Marked closed - no deliveries',
              tone: AdminStatusTone.warning,
            ),
        ],
      ),
    );
  }

  static String _windowText(StoreStatus status) {
    final date = status.deliveryDate;
    if (date == null) return 'not scheduled';
    return '${AdminFormat.relativeDay(date)} at ${_time(status.deliveryStartTime)}';
  }

  static String _time(String? raw) {
    if (raw == null || raw.isEmpty) return 'opening time';
    final parts = raw.split(':');
    final hour = int.tryParse(parts.first);
    if (hour == null) return raw;
    final minute = parts.length > 1 ? int.tryParse(parts[1]) ?? 0 : 0;
    final suffix = hour < 12 ? 'AM' : 'PM';
    final twelve = hour % 12 == 0 ? 12 : hour % 12;
    return minute == 0
        ? '$twelve $suffix'
        : '$twelve:${minute.toString().padLeft(2, '0')} $suffix';
  }
}

class _Fact extends StatelessWidget {
  const _Fact({
    required this.icon,
    required this.label,
    required this.value,
    this.tone = AdminStatusTone.neutral,
  });

  final IconData icon;
  final String label;
  final String value;
  final AdminStatusTone tone;

  @override
  Widget build(BuildContext context) {
    final color = switch (tone) {
      AdminStatusTone.success => AdminColors.success,
      AdminStatusTone.warning => AdminColors.warning,
      AdminStatusTone.danger => AdminColors.danger,
      _ => AdminColors.textSecondary,
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: AdminSpacing.sm),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: AdminSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(label, style: AdminText.caption),
                Text(
                  value,
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w600,
                    color: tone == AdminStatusTone.neutral
                        ? AdminColors.textPrimary
                        : color,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// The three-way switch.
class _AcceptanceCard extends ConsumerStatefulWidget {
  const _AcceptanceCard({required this.operations});

  final StoreOperations operations;

  @override
  ConsumerState<_AcceptanceCard> createState() => _AcceptanceCardState();
}

class _AcceptanceCardState extends ConsumerState<_AcceptanceCard> {
  late final TextEditingController _message =
      TextEditingController(text: widget.operations.closureMessage ?? '');
  bool _saving = false;

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  /// Pausing orders stops the shop earning, so it asks first.
  ///
  /// Only pausing asks. Turning orders back ON is the safe direction and a
  /// confirmation there is a dialog people learn to dismiss without reading -
  /// which is how the one that matters gets dismissed too.
  Future<bool> _confirmIfPausing(StoreOrderAcceptance next) async {
    if (next != StoreOrderAcceptance.off) return true;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Pause new orders?'),
        content: const Text(
          'Customers can still browse and fill their baskets, but checkout '
          'will be refused until you turn orders back on.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Pause orders'),
          ),
        ],
      ),
    );
    return confirmed ?? false;
  }

  Future<void> _apply(StoreOrderAcceptance next) async {
    if (!await _confirmIfPausing(next)) return;
    setState(() => _saving = true);
    try {
      await ref.read(storeOperationsRepositoryProvider).setAcceptance(
            next,
            closureMessage: _message.text.trim(),
          );
      if (mounted) ref.invalidate(storeOperationsProvider);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final current = widget.operations.acceptance;

    return AdminSectionCard(
      title: 'Taking orders',
      subtitle: 'Browsing stays open whatever you choose here.',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_saving) const LinearProgressIndicator(),
          for (final option in StoreOrderAcceptance.values)
            RadioListTile<StoreOrderAcceptance>(
              value: option,
              groupValue: current,
              onChanged: _saving ? null : (value) {
                if (value != null && value != current) _apply(value);
              },
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: Text(
                option.label,
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 14,
                ),
              ),
              subtitle: Text(option.explanation, style: AdminText.caption),
            ),
          const SizedBox(height: AdminSpacing.sm),
          TextField(
            controller: _message,
            maxLength: 300,
            decoration: const InputDecoration(
              labelText: 'What customers see while orders are paused',
              hintText: 'Back at 9am',
              helperText:
                  'Shown in the app instead of a generic message. Saved when '
                  'you change the setting above.',
            ),
          ),
          if (widget.operations.updatedBy != null)
            Padding(
              padding: const EdgeInsets.only(top: AdminSpacing.sm),
              child: Text(
                'Last changed by ${widget.operations.updatedBy}',
                style: AdminText.caption,
              ),
            ),
        ],
      ),
    );
  }
}

/// Days the vans do not run.
class _ClosuresCard extends ConsumerWidget {
  const _ClosuresCard({required this.operations});

  final StoreOperations operations;

  Future<void> _add(BuildContext context, WidgetRef ref) async {
    final now = DateTime.now();
    final date = await showDatePicker(
      context: context,
      initialDate: now,
      // The past cannot be closed - closing a day that already happened
      // cannot stop a delivery that already went out. The server rejects it
      // too; this stops the operator getting that far.
      firstDate: DateTime(now.year, now.month, now.day),
      lastDate: now.add(const Duration(days: 400)),
    );
    if (date == null || !context.mounted) return;

    // Owned here rather than created inside the builder: a builder runs on
    // every rebuild of the dialog, and a controller made there is both leaked
    // and silently replaced mid-typing.
    final controller = TextEditingController();
    // Nullable and non-final: a `final` local assigned inside a try is
    // "potentially unassigned" to Dart's flow analysis at the read below,
    // which is a compile error rather than a lint.
    String? reason;
    try {
      reason = await showDialog<String>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text('Close ${AdminFormat.relativeDay(date)}?'),
          content: TextField(
            controller: controller,
            autofocus: true,
            maxLength: 300,
            decoration: const InputDecoration(
              labelText: 'Reason (shown to customers)',
              hintText: 'Holi',
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () =>
                  Navigator.of(dialogContext).pop(controller.text.trim()),
              child: const Text('Close this day'),
            ),
          ],
        ),
      );
    } finally {
      controller.dispose();
    }
    if (reason == null || !context.mounted) return;

    try {
      await ref
          .read(storeOperationsRepositoryProvider)
          .addClosure(date, reason.isEmpty ? null : reason);
      // Guarded: the screen can be popped while the request is in flight, and
      // invalidating through a disposed ref throws.
      if (context.mounted) ref.invalidate(storeOperationsProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    }
  }

  Future<void> _remove(
      BuildContext context, WidgetRef ref, StoreClosure closure) async {
    try {
      await ref
          .read(storeOperationsRepositoryProvider)
          .removeClosure(closure.date);
      if (context.mounted) ref.invalidate(storeOperationsProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final closures = operations.closures;

    return AdminSectionCard(
      title: 'Closed days',
      subtitle: 'No deliveries. Orders are still taken and go out on the '
          'next open day.',
      trailing: IconButton(
        icon: const Icon(Icons.add),
        tooltip: 'Close a day',
        onPressed: hapticize(() => _add(context, ref)),
      ),
      child: closures.isEmpty
          ? const Padding(
              padding: EdgeInsets.symmetric(vertical: AdminSpacing.md),
              child: Text(
                'No closed days coming up. Deliveries run every day.',
                style: AdminText.caption,
              ),
            )
          : Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final closure in closures)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                    leading: const Icon(Icons.event_busy_outlined,
                        color: AdminColors.warning),
                    title: Text(
                      AdminFormat.relativeDay(closure.date),
                      style: const TextStyle(
                          fontWeight: FontWeight.w600, fontSize: 14),
                    ),
                    subtitle: closure.reason == null
                        ? null
                        : Text(closure.reason!, style: AdminText.caption),
                    trailing: IconButton(
                      icon: const Icon(Icons.close, size: 18),
                      tooltip: 'Reopen this day',
                      onPressed:
                          hapticize(() => _remove(context, ref, closure)),
                    ),
                  ),
              ],
            ),
    );
  }
}

class _OperationsSkeleton extends StatelessWidget {
  const _OperationsSkeleton();

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(AdminSpacing.lg),
      children: const [
        AdminSkeleton(height: 150),
        SizedBox(height: AdminSpacing.lg),
        AdminSkeleton(height: 220),
        SizedBox(height: AdminSpacing.lg),
        AdminSkeleton(height: 120),
      ],
    );
  }
}
