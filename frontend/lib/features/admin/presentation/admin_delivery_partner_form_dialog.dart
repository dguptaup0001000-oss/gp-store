import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../domain/delivery_partner_models.dart';
import '../domain/worker_login_account.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminDeliveryPartnerFormDialog extends ConsumerStatefulWidget {
  const AdminDeliveryPartnerFormDialog({super.key, this.partner});

  /// Null means "add new partner" - non-null means editing this one.
  final DeliveryPartnerModel? partner;

  @override
  ConsumerState<AdminDeliveryPartnerFormDialog> createState() => _AdminDeliveryPartnerFormDialogState();
}

class _AdminDeliveryPartnerFormDialogState extends ConsumerState<AdminDeliveryPartnerFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController;
  late final TextEditingController _mobileController;
  late final TextEditingController _vehicleNumberController;
  late String _vehicleType;
  late bool _available;
  bool _isSaving = false;

  // The worker-app login. Loaded separately from the roster fields because it
  // lives on a different record - the Customer account - and because reading
  // it has to happen server-side inside a transaction (the association is
  // lazy and the backend runs with open-in-view off).
  WorkerLoginAccount? _login;
  bool _loadingLogin = false;

  /// Whether the read above failed, kept apart from "nothing is linked".
  ///
  /// The status line makes a definite claim either way, and "not set up yet"
  /// when we simply could not find out is a claim we have not earned - it
  /// would send the shopkeeper to fix something that may already be fine.
  bool _loginLoadFailed = false;
  late final TextEditingController _loginEmailController;
  late final TextEditingController _loginPasswordController;
  bool _showPassword = false;

  /// A refusal about the login email specifically, shown under that field.
  ///
  /// Under the field and not in a SnackBar: the dialog stays open so the
  /// address can be corrected, and a SnackBar slides in behind it.
  String? _loginError;

  bool get _isEditing => widget.partner != null;

  @override
  void initState() {
    super.initState();
    final p = widget.partner;
    _nameController = TextEditingController(text: p?.name ?? '');
    _mobileController = TextEditingController(text: p?.mobile ?? '');
    _vehicleNumberController = TextEditingController(text: p?.vehicleNumber ?? '');
    _vehicleType = p?.vehicleType ?? 'BIKE';
    _available = p?.available ?? true;
    _loginEmailController = TextEditingController();
    _loginPasswordController = TextEditingController();
    final partnerId = p?.id;
    if (partnerId != null) {
      // Assigned directly rather than through setState: initState runs inside
      // the parent's build pass, and setState there is markNeedsBuild during
      // build, which throws. The first build has not happened yet, so the
      // spinner shows anyway.
      _loadingLogin = true;
      _loadLogin(partnerId);
    }
  }

  /// Called once, from initState, which is why it does not raise the spinner
  /// itself - see the note there.
  Future<void> _loadLogin(int partnerId) async {
    try {
      final login =
          await ref.read(adminProductsRepositoryProvider).getWorkerLoginAccount(partnerId);
      if (!mounted) return;
      setState(() {
        _login = login;
        _loginLoadFailed = false;
        _loginEmailController.text = login.email ?? '';
      });
    } catch (_) {
      // Not fatal: the rest of the dialog still edits the roster record, so
      // this says so rather than blocking Save. Reopening the dialog retries.
      if (mounted) {
        setState(() {
          _login = null;
          _loginLoadFailed = true;
        });
      }
    } finally {
      if (mounted) setState(() => _loadingLogin = false);
    }
  }

  /// Applies whatever the login-email field says, as part of Save.
  ///
  /// THIS USED TO BE ITS OWN BUTTON, and that was wrong. The address sits in
  /// a text field, in a form, above a Save button - so Save is what somebody
  /// presses, and it silently discarded the address they had just typed. The
  /// rider stayed locked out and the screen reported success. A field that
  /// only takes effect via a second, separate button is a trap.
  ///
  /// Runs BEFORE the roster update: it is the part that can be refused (an
  /// unregistered address, an account with no password, one already used by
  /// another rider), so a refusal leaves the whole record untouched and the
  /// dialog open on the field that has to change.
  ///
  /// Returns true when saving may continue.
  Future<bool> _applyLoginChange() async {
    final partnerId = widget.partner?.id;
    if (partnerId == null) return true;

    final wanted = _loginEmailController.text.trim();
    final password = _loginPasswordController.text;
    final current = _login?.email?.trim() ?? '';
    // Case-insensitive on the address: the server matches it that way, so
    // differing only in case is not a change. A typed password IS a change
    // even when the address is identical - that is how a rider's password
    // gets reset when they forget it.
    final sameEmail = wanted.toLowerCase() == current.toLowerCase();
    if (sameEmail && password.isEmpty) return true;

    final repository = ref.read(adminProductsRepositoryProvider);
    try {
      final login = wanted.isEmpty
          ? await repository.unlinkWorkerLoginAccount(partnerId)
          : await repository.linkWorkerLoginAccount(partnerId, wanted, password);
      if (mounted) {
        setState(() {
          _login = login;
          // Never leave a credential sitting in a text field on a shared
          // shop phone once it has been accepted.
          _loginPasswordController.clear();
          _showPassword = false;
        });
      }
      return true;
    } catch (e) {
      // The backend's refusals name the next step ("ask them to register in
      // the customer app first"), so they are shown as-is.
      if (mounted) setState(() => _loginError = extractErrorMessage(e));
      return false;
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _mobileController.dispose();
    _vehicleNumberController.dispose();
    _loginEmailController.dispose();
    _loginPasswordController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _isSaving = true;
      _loginError = null;
    });

    try {
      // First, because it is the fallible half - see _applyLoginChange.
      if (!await _applyLoginChange()) {
        return;
      }
      if (!mounted) return;

      final repository = ref.read(adminProductsRepositoryProvider);

      final model = DeliveryPartnerModel(
        id: widget.partner?.id,
        name: _nameController.text.trim(),
        mobile: _mobileController.text.trim(),
        vehicleType: _vehicleType,
        vehicleNumber: _vehicleNumberController.text.trim().isEmpty ? null : _vehicleNumberController.text.trim(),
        available: _available,
        active: true,
      );

      if (_isEditing) {
        await repository.updateDeliveryPartner(model);
      } else {
        await repository.createDeliveryPartner(model);
      }

      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  /// Only shown when editing: linking needs a partner id, and a rider who does
  /// not exist yet cannot be given a login.
  Widget _buildWorkerLoginSection(BuildContext context) {
    if (_loadingLogin) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 8),
        child: Center(
          child: SizedBox(
              height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      );
    }

    final login = _login;
    final linked = login?.linked ?? false;
    final canSignIn = login?.canSignIn ?? false;
    final ready = linked && canSignIn && !_loginLoadFailed;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Worker app sign-in',
            style: Theme.of(context)
                .textTheme
                .titleSmall
                ?.copyWith(fontWeight: FontWeight.w600)),
        const SizedBox(height: 4),
        Text(
          // The shop CREATES these credentials and tells the rider. No
          // self-registration, no second app, no OTP - which is what the
          // earlier wording asked for and what nothing could deliver.
          'Choose the email and password this rider will type into the worker '
          'app, then press Save and tell them. If they already shop here with '
          'that email, leave the password blank and they keep the one they '
          'have - the same account works for both apps. Clear the email and '
          'Save to take their access away.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _loginEmailController,
          keyboardType: TextInputType.emailAddress,
          autocorrect: false,
          enabled: !_isSaving,
          onChanged: (_) {
            // A refusal is about the address that caused it, so it stops
            // being shown the moment that address is edited.
            if (_loginError != null) setState(() => _loginError = null);
          },
          decoration: InputDecoration(
            labelText: 'Login email',
            hintText: 'rider@gmail.com',
            errorText: _loginError,
            // The refusals name a next step and do not fit one line.
            errorMaxLines: 4,
          ),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _loginPasswordController,
          obscureText: !_showPassword,
          autocorrect: false,
          enableSuggestions: false,
          enabled: !_isSaving,
          decoration: InputDecoration(
            labelText: linked ? 'New password (optional)' : 'Password',
            // A rider can already be a customer here, with a password of their
            // own that the shop must not reset by accident just because this
            // field looked mandatory.
            helperText: linked
                ? 'Only fill this in to change it'
                : 'At least 8 characters. Leave blank if they already shop '
                    'here with this email.',
            helperMaxLines: 3,
            // Shown on request: the shopkeeper has to read this out to the
            // rider, and typing a password they cannot see into a phone twice
            // is how the wrong one gets set.
            suffixIcon: IconButton(
              icon: Icon(_showPassword ? Icons.visibility_off : Icons.visibility),
              tooltip: _showPassword ? 'Hide password' : 'Show password',
              onPressed: _isSaving
                  ? null
                  : () => setState(() => _showPassword = !_showPassword),
            ),
          ),
        ),
        const SizedBox(height: 8),
        // What the rider can do RIGHT NOW, in one line. "Linked" on its own
        // would hide the state every partner used to be in - an OTP account
        // with no password, attached perfectly well and unable to sign in.
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              ready ? Icons.check_circle_outline : Icons.info_outline,
              size: 16,
              color: ready
                  ? Theme.of(context).colorScheme.primary
                  : Theme.of(context).colorScheme.error,
            ),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                _loginLoadFailed
                    ? 'Could not read the current sign-in for this rider. '
                        'Setting an address below still works.'
                    : !linked
                        ? 'Not set up yet - set an email and password above.'
                        : canSignIn
                            ? 'Set up. This rider can sign in to the worker app.'
                            : 'Attached, but that account has no password, so it '
                                'still cannot sign in. Ask them to set one in the '
                                'customer app.',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: ready
                          ? Theme.of(context).colorScheme.primary
                          : Theme.of(context).colorScheme.error,
                    ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isEditing ? 'Edit Delivery Partner' : 'Add Delivery Partner'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _nameController,
                decoration: const InputDecoration(labelText: 'Full name'),
                validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _mobileController,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(labelText: 'Mobile number'),
                validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _vehicleType,
                decoration: const InputDecoration(labelText: 'Vehicle type'),
                items: const [
                  DropdownMenuItem(value: 'BIKE', child: Text('Bike')),
                  DropdownMenuItem(value: 'PICKUP', child: Text('Pickup (for bulk orders)')),
                ],
                onChanged: hapticizeValue((value) => setState(() => _vehicleType = value!)),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _vehicleNumberController,
                decoration: const InputDecoration(labelText: 'Vehicle number (optional)'),
              ),
              const SizedBox(height: 8),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Available for new deliveries'),
                value: _available,
                onChanged: hapticizeValue((value) => setState(() => _available = value)),
              ),
              if (_isEditing) ...[
                const Divider(height: 24),
                _buildWorkerLoginSection(context),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: hapticize(() => Navigator.of(context).pop(false)), child: const Text('Cancel')),
        FilledButton(
          onPressed: _isSaving ? null : _save,
          child: _isSaving
              ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}
