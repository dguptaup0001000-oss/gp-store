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
  bool _savingLogin = false;
  late final TextEditingController _loginEmailController;

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
        _loginEmailController.text = login.email ?? '';
      });
    } catch (_) {
      // Not fatal: the rest of the dialog still edits the roster record, so
      // this leaves the section in its empty state rather than blocking Save.
      // Reopening the dialog retries.
      if (mounted) setState(() => _login = null);
    } finally {
      if (mounted) setState(() => _loadingLogin = false);
    }
  }

  Future<void> _linkLogin() async {
    final partnerId = widget.partner?.id;
    if (partnerId == null) return;
    final email = _loginEmailController.text.trim();
    if (email.isEmpty) return;

    setState(() => _savingLogin = true);
    try {
      final login = await ref
          .read(adminProductsRepositoryProvider)
          .linkWorkerLoginAccount(partnerId, email);
      if (!mounted) return;
      setState(() {
        _login = login;
        _loginEmailController.text = login.email ?? '';
      });
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('This rider can now sign in to the worker app.'),
      ));
    } catch (e) {
      if (!mounted) return;
      // The backend's refusals name the next step ("ask them to register in
      // the customer app first"), so they are shown as-is.
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _savingLogin = false);
    }
  }

  Future<void> _unlinkLogin() async {
    final partnerId = widget.partner?.id;
    if (partnerId == null) return;

    setState(() => _savingLogin = true);
    try {
      final login = await ref
          .read(adminProductsRepositoryProvider)
          .unlinkWorkerLoginAccount(partnerId);
      if (!mounted) return;
      setState(() {
        _login = login;
        _loginEmailController.clear();
      });
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _savingLogin = false);
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _mobileController.dispose();
    _vehicleNumberController.dispose();
    _loginEmailController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSaving = true);

    try {
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
          // Says what to do BEFORE they try an address that will be refused.
          'The worker app signs in with an email and password. Enter the '
          'address of an account they have already registered in the '
          'customer app.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _loginEmailController,
          keyboardType: TextInputType.emailAddress,
          autocorrect: false,
          decoration: const InputDecoration(
            labelText: 'Login email',
            hintText: 'rider@gmail.com',
          ),
        ),
        // Linked but unusable is a real state, and the one every partner
        // created by this screen used to be in - an OTP account with no
        // password. Saying "linked" alone would hide that.
        if (linked && !canSignIn) ...[
          const SizedBox(height: 8),
          Text(
            'Linked, but this account has no password, so it cannot sign in '
            'to the worker app yet.',
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(color: Theme.of(context).colorScheme.error),
          ),
        ],
        const SizedBox(height: 8),
        Row(
          children: [
            FilledButton.tonal(
              onPressed: _savingLogin ? null : _linkLogin,
              child: _savingLogin
                  ? const SizedBox(
                      height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(linked ? 'Update login' : 'Link login'),
            ),
            if (linked) ...[
              const SizedBox(width: 8),
              TextButton(
                onPressed: _savingLogin ? null : _unlinkLogin,
                child: const Text('Unlink'),
              ),
            ],
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
