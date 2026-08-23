import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../domain/delivery_partner_models.dart';
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
  }

  @override
  void dispose() {
    _nameController.dispose();
    _mobileController.dispose();
    _vehicleNumberController.dispose();
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
