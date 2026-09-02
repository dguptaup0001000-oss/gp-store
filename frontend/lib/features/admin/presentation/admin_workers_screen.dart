import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../domain/worker_models.dart';
import 'admin_providers.dart';

/// The shop's delivery workers: hire, edit, pause, remove.
///
/// ONE PAGE, AND ONE WAY TO SET A LOGIN. The previous arrangement had the
/// roster in one place and the worker's credentials attached somewhere else,
/// through a customer account - which is how a shop ended up with a rider who
/// could not sign in and no screen that explained why. Here the login is part
/// of the worker.
///
/// WHAT IS REQUIRED IS WHAT LETS THEM WORK: an email and a password. Phone,
/// vehicle and registration are recorded when the shop has them and never
/// block hiring somebody.
class AdminWorkersScreen extends ConsumerWidget {
  const AdminWorkersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final workers = ref.watch(adminWorkersProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Delivery Workers')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _openForm(context, ref, null),
        icon: const Icon(Icons.person_add_alt),
        label: const Text('Add worker'),
      ),
      body: workers.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => _Retry(
          message: extractErrorMessage(e),
          onRetry: () => ref.invalidate(adminWorkersProvider),
        ),
        data: (list) {
          if (list.isEmpty) {
            return const _Empty();
          }
          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(adminWorkersProvider),
            child: ListView.separated(
              padding: const EdgeInsets.fromLTRB(12, 12, 12, 96),
              itemCount: list.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, i) => _WorkerCard(
                worker: list[i],
                onEdit: () => _openForm(context, ref, list[i]),
                onPause: () => _openPause(context, ref, list[i]),
                onResume: () => _run(context, ref,
                    () => ref.read(adminWorkersRepositoryProvider).resume(list[i].id)),
                onDelete: () => _confirmDelete(context, ref, list[i]),
              ),
            ),
          );
        },
      ),
    );
  }

  /// Every write goes through here: run it, say what happened, refetch.
  ///
  /// REFETCHING RATHER THAN PATCHING THE LIST IN PLACE is deliberate - a pause
  /// can expire between two taps, so the server's answer is the only one worth
  /// drawing.
  static Future<void> _run(
      BuildContext context, WidgetRef ref, Future<void> Function() action) async {
    try {
      await action();
      ref.invalidate(adminWorkersProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e)), duration: const Duration(seconds: 6)),
      );
    }
  }

  static Future<void> _openForm(BuildContext context, WidgetRef ref, AdminWorker? worker) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => AdminWorkerFormDialog(worker: worker),
    );
    if (saved == true) ref.invalidate(adminWorkersProvider);
  }

  static Future<void> _openPause(BuildContext context, WidgetRef ref, AdminWorker worker) async {
    final choice = await showDialog<_PauseChoice>(
      context: context,
      builder: (_) => _PauseDialog(workerName: worker.name),
    );
    if (choice == null) return;
    if (!context.mounted) return;
    await _run(
        context,
        ref,
        () => ref.read(adminWorkersRepositoryProvider).suspend(
              worker.id,
              minutes: choice.minutes,
              reason: choice.reason,
            ));
  }

  static Future<void> _confirmDelete(
      BuildContext context, WidgetRef ref, AdminWorker worker) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Remove ${worker.name}?'),
        content: const Text(
          'They will not be able to sign in to the worker app again, and they '
          'disappear from the roster.\n\n'
          'Deliveries they have already made are kept, so your records stay '
          'correct. Their email and phone number become free to give to '
          'somebody else.',
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(dialogContext).colorScheme.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    await _run(context, ref,
        () => ref.read(adminWorkersRepositoryProvider).remove(worker.id));
  }
}

class _WorkerCard extends StatelessWidget {
  const _WorkerCard({
    required this.worker,
    required this.onEdit,
    required this.onPause,
    required this.onResume,
    required this.onDelete,
  });

  final AdminWorker worker;
  final VoidCallback onEdit;
  final VoidCallback onPause;
  final VoidCallback onResume;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    // The status colour carries the same information as the words, so the page
    // can be read at a glance without reading every line.
    final Color tone = worker.suspended
        ? scheme.tertiary
        : worker.canSignIn
            ? scheme.primary
            : scheme.error;

    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 6, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  backgroundColor: tone.withValues(alpha: 0.15),
                  foregroundColor: tone,
                  child: Icon(worker.suspended
                      ? Icons.pause
                      : worker.canSignIn
                          ? Icons.two_wheeler
                          : Icons.block),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(worker.name,
                          style: Theme.of(context)
                              .textTheme
                              .titleMedium
                              ?.copyWith(fontWeight: FontWeight.w600)),
                      const SizedBox(height: 2),
                      // Both identifiers, because both of them work at the
                      // login screen and the shop reads these out.
                      Text(
                        [worker.loginEmail, worker.mobile]
                            .whereType<String>()
                            .where((v) => v.isNotEmpty)
                            .join('  ·  '),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Icon(Icons.circle, size: 10, color: tone),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    worker.suspended && worker.suspendedUntil != null
                        ? '${worker.statusLine} · until ${_until(worker.suspendedUntil!)}'
                        : worker.statusLine,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(color: tone),
                  ),
                ),
              ],
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton.icon(
                  onPressed: onEdit,
                  icon: const Icon(Icons.edit_outlined, size: 18),
                  label: const Text('Edit'),
                ),
                if (worker.suspended)
                  TextButton.icon(
                    onPressed: onResume,
                    icon: const Icon(Icons.play_arrow, size: 18),
                    label: const Text('Resume'),
                  )
                else
                  TextButton.icon(
                    onPressed: onPause,
                    icon: const Icon(Icons.pause_circle_outline, size: 18),
                    label: const Text('Pause'),
                  ),
                IconButton(
                  tooltip: 'Remove worker',
                  onPressed: onDelete,
                  icon: Icon(Icons.delete_outline,
                      color: Theme.of(context).colorScheme.error),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// Time for today, date and time for anything later - a shopkeeper reading
  /// "until 17:30" should not have to wonder which day that is.
  static String _until(DateTime until) {
    final now = DateTime.now();
    final clock =
        '${until.hour.toString().padLeft(2, '0')}:${until.minute.toString().padLeft(2, '0')}';
    final sameDay =
        until.year == now.year && until.month == now.month && until.day == now.day;
    return sameDay ? clock : '${until.day}/${until.month} $clock';
  }
}

/// Add or edit. The only mandatory fields are the ones that let them sign in.
class AdminWorkerFormDialog extends ConsumerStatefulWidget {
  const AdminWorkerFormDialog({super.key, this.worker});

  final AdminWorker? worker;

  @override
  ConsumerState<AdminWorkerFormDialog> createState() => _AdminWorkerFormDialogState();
}

class _AdminWorkerFormDialogState extends ConsumerState<AdminWorkerFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _name;
  late final TextEditingController _email;
  late final TextEditingController _mobile;
  late final TextEditingController _vehicleNumber;
  final _password = TextEditingController();

  String _vehicleType = 'BIKE';
  bool _available = true;
  bool _showPassword = false;
  bool _saving = false;
  String? _error;

  bool get _isEditing => widget.worker != null;

  @override
  void initState() {
    super.initState();
    final worker = widget.worker;
    _name = TextEditingController(text: worker?.name ?? '');
    _email = TextEditingController(text: worker?.loginEmail ?? '');
    _mobile = TextEditingController(text: worker?.mobile ?? '');
    _vehicleNumber = TextEditingController(text: worker?.vehicleNumber ?? '');
    _vehicleType = worker?.vehicleType ?? 'BIKE';
    _available = worker?.available ?? true;
  }

  @override
  void dispose() {
    _name.dispose();
    _email.dispose();
    _mobile.dispose();
    _vehicleNumber.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final repository = ref.read(adminWorkersRepositoryProvider);
      final mobile = _mobile.text.trim();
      if (_isEditing) {
        await repository.update(
          widget.worker!.id,
          name: _name.text.trim(),
          loginEmail: _email.text.trim(),
          // Blank means "keep the one they have" - the server decides that,
          // so editing a vehicle cannot reset a working password.
          password: _password.text,
          mobile: mobile.isEmpty ? null : mobile,
          vehicleType: _vehicleType,
          vehicleNumber: _vehicleNumber.text.trim().isEmpty
              ? null
              : _vehicleNumber.text.trim(),
          available: _available,
        );
      } else {
        await repository.create(
          name: _name.text.trim(),
          loginEmail: _email.text.trim(),
          password: _password.text,
          mobile: mobile.isEmpty ? null : mobile,
          vehicleType: _vehicleType,
          vehicleNumber: _vehicleNumber.text.trim().isEmpty
              ? null
              : _vehicleNumber.text.trim(),
          available: _available,
        );
      }
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      final message = extractErrorMessage(e);
      if (!mounted) return;
      setState(() => _error = message);
      // In a SnackBar as well as inline: this dialog scrolls and the keyboard
      // covers the bottom of it, so an inline-only refusal reads as "Save did
      // nothing" and gets pressed again.
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(message), duration: const Duration(seconds: 6)),
      );
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isEditing ? 'Edit worker' : 'Add worker'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextFormField(
                controller: _name,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(labelText: 'Full name'),
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Enter their name' : null,
              ),
              const SizedBox(height: 12),
              Text('Worker app sign-in',
                  style: Theme.of(context)
                      .textTheme
                      .titleSmall
                      ?.copyWith(fontWeight: FontWeight.w600)),
              const SizedBox(height: 4),
              Text(
                'These are the two things the rider types into the worker app. '
                'They can sign in with either the email or the phone number '
                'below, and this password. Tell them both.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 8),
              TextFormField(
                controller: _email,
                keyboardType: TextInputType.emailAddress,
                autocorrect: false,
                decoration: const InputDecoration(labelText: 'Email (required)'),
                validator: (v) {
                  final value = (v ?? '').trim();
                  if (value.isEmpty) return 'An email is required - it is how they sign in';
                  if (!value.contains('@')) return 'That does not look like an email address';
                  return null;
                },
              ),
              const SizedBox(height: 8),
              TextFormField(
                controller: _password,
                obscureText: !_showPassword,
                decoration: InputDecoration(
                  labelText: _isEditing ? 'New password (optional)' : 'Password (required)',
                  helperText: _isEditing
                      ? 'Leave blank to keep their current password'
                      : 'At least 8 characters',
                  helperMaxLines: 2,
                  // Shown on request: the shopkeeper reads this out to the
                  // rider, and typing an invisible password into a phone twice
                  // is how the wrong one gets set.
                  suffixIcon: IconButton(
                    icon: Icon(_showPassword ? Icons.visibility_off : Icons.visibility),
                    onPressed: () => setState(() => _showPassword = !_showPassword),
                  ),
                ),
                validator: (v) {
                  final value = v ?? '';
                  if (_isEditing && value.isEmpty) return null;
                  if (value.length < 8) return 'At least 8 characters';
                  return null;
                },
              ),
              const SizedBox(height: 8),
              TextFormField(
                controller: _mobile,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(
                  labelText: 'Phone number (optional)',
                  helperText: 'They can sign in with this too',
                ),
                validator: (v) {
                  final digits = (v ?? '').replaceAll(RegExp(r'\D'), '');
                  if (digits.isEmpty) return null;
                  return digits.length == 10 || digits.length == 12
                      ? null
                      : 'A phone number must be 10 digits';
                },
              ),
              const Divider(height: 28),
              DropdownButtonFormField<String>(
                initialValue: _vehicleType,
                decoration: const InputDecoration(labelText: 'Vehicle type'),
                items: const [
                  DropdownMenuItem(value: 'BIKE', child: Text('Bike')),
                  DropdownMenuItem(value: 'SCOOTER', child: Text('Scooter')),
                  DropdownMenuItem(value: 'CYCLE', child: Text('Cycle')),
                  DropdownMenuItem(value: 'VAN', child: Text('Van')),
                ],
                onChanged: (v) => setState(() => _vehicleType = v ?? 'BIKE'),
              ),
              const SizedBox(height: 8),
              TextFormField(
                controller: _vehicleNumber,
                textCapitalization: TextCapitalization.characters,
                decoration:
                    const InputDecoration(labelText: 'Vehicle number (optional)'),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Available for new deliveries'),
                value: _available,
                onChanged: (v) => setState(() => _available = v),
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _save,
          child: Text(_saving ? 'Saving...' : 'Save'),
        ),
      ],
    );
  }
}

class _PauseChoice {
  const _PauseChoice(this.minutes, this.reason);
  final int minutes;
  final String? reason;
}

/// "Closed for a while", in the units a shop actually thinks in.
///
/// PRESETS RATHER THAN A DATE PICKER. The shop is deciding "not for the rest
/// of this shift" or "not today", standing at a counter - asking them to
/// construct a timestamp is the wrong question.
class _PauseDialog extends StatefulWidget {
  const _PauseDialog({required this.workerName});
  final String workerName;

  @override
  State<_PauseDialog> createState() => _PauseDialogState();
}

class _PauseDialogState extends State<_PauseDialog> {
  static const _options = <String, int>{
    '1 hour': 60,
    '4 hours': 240,
    'Rest of today': 720,
    '1 day': 1440,
    '1 week': 10080,
  };

  String _selected = '1 hour';
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Pause ${widget.workerName}'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'They cannot sign in, and if their app is open it stops working '
            'straight away. Access comes back on its own when the time is up.',
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            children: _options.keys
                .map((label) => ChoiceChip(
                      label: Text(label),
                      selected: _selected == label,
                      onSelected: (_) => setState(() => _selected = label),
                    ))
                .toList(),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _reason,
            decoration: const InputDecoration(
              labelText: 'Reason (optional)',
              helperText: 'Shown to them on the sign-in screen',
              helperMaxLines: 2,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(_PauseChoice(
            _options[_selected]!,
            _reason.text.trim().isEmpty ? null : _reason.text.trim(),
          )),
          child: const Text('Pause'),
        ),
      ],
    );
  }
}

class _Empty extends StatelessWidget {
  const _Empty();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.two_wheeler, size: 48),
            const SizedBox(height: 12),
            Text('No delivery workers yet',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 6),
            Text(
              'Add one and give them an email and a password. That is all they '
              'need to sign in to the worker app.',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }
}

class _Retry extends StatelessWidget {
  const _Retry({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton(onPressed: onRetry, child: const Text('Try again')),
          ],
        ),
      ),
    );
  }
}
