import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import '../../products/domain/product_models.dart';
import 'admin_providers.dart';

class AdminVariantFormDialog extends ConsumerStatefulWidget {
  const AdminVariantFormDialog({super.key, required this.productId, this.variant});

  final int productId;

  /// Null means "add new variant" - non-null means editing this one.
  final ProductVariant? variant;

  @override
  ConsumerState<AdminVariantFormDialog> createState() => _AdminVariantFormDialogState();
}

class _AdminVariantFormDialogState extends ConsumerState<AdminVariantFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _quantityController;
  late final TextEditingController _unitController;
  late final TextEditingController _mrpController;
  late final TextEditingController _sellingPriceController;
  late final TextEditingController _costPriceController;
  late final TextEditingController _imageUrlController;
  late bool _available;
  bool _isSaving = false;

  bool get _isEditing => widget.variant != null;

  @override
  void initState() {
    super.initState();
    final v = widget.variant;
    _quantityController = TextEditingController(text: v?.quantity?.toString() ?? '');
    _unitController = TextEditingController(text: v?.unit ?? '');
    _mrpController = TextEditingController(text: v?.mrp?.toString() ?? '');
    _sellingPriceController = TextEditingController(text: v?.sellingPrice.toString() ?? '');
    // costPrice is deliberately never populated for edit - the backend never
    // returns it (WRITE_ONLY, see ProductVariant.costPrice), so there's
    // nothing to prefill. Leaving it blank means "no change" is NOT what
    // happens here though - see the save-time warning below.
    _costPriceController = TextEditingController();
    _imageUrlController = TextEditingController(text: v?.imageUrl ?? '');
    _available = v?.available ?? true;
  }

  @override
  void dispose() {
    _quantityController.dispose();
    _unitController.dispose();
    _mrpController.dispose();
    _sellingPriceController.dispose();
    _costPriceController.dispose();
    _imageUrlController.dispose();
    super.dispose();
  }

  Future<void> _save({bool allowBelowCost = false}) async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSaving = true);

    try {
      final repository = ref.read(adminProductsRepositoryProvider);
      final quantity = double.parse(_quantityController.text.trim());
      final mrp = double.parse(_mrpController.text.trim());
      final sellingPrice = double.parse(_sellingPriceController.text.trim());
      final costPrice = _costPriceController.text.trim().isEmpty
          ? null
          : double.parse(_costPriceController.text.trim());
      final imageUrl = _imageUrlController.text.trim().isEmpty ? null : _imageUrlController.text.trim();

      if (_isEditing) {
        await repository.updateVariant(
          variantId: widget.variant!.id,
          quantity: quantity,
          unit: _unitController.text.trim(),
          imageUrl: imageUrl,
          mrp: mrp,
          sellingPrice: sellingPrice,
          costPrice: costPrice,
          available: _available,
          allowBelowCost: allowBelowCost,
        );
      } else {
        await repository.createVariant(
          productId: widget.productId,
          quantity: quantity,
          unit: _unitController.text.trim(),
          imageUrl: imageUrl,
          mrp: mrp,
          sellingPrice: sellingPrice,
          costPrice: costPrice,
          allowBelowCost: allowBelowCost,
        );
      }

      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      final message = extractErrorMessage(e);

      // The backend deliberately blocks selling-below-cost by default (catches
      // typos) but allows it when explicitly confirmed (loss-leader pricing is
      // a legitimate real choice) - surface that choice here rather than just
      // showing a dead-end error.
      if (message.contains('below cost price') && !allowBelowCost) {
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('Selling price is below cost'),
            content: const Text('This means you\'d lose money on every sale. Save anyway?'),
            actions: [
              TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
              TextButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Save anyway')),
            ],
          ),
        );
        if (confirmed == true) {
          await _save(allowBelowCost: true);
          return;
        }
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text("Couldn't save variant - please check the values and try again")),
        );
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isEditing ? 'Edit Variant' : 'Add Variant'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Expanded(
                    child: TextFormField(
                      controller: _quantityController,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(labelText: 'Pack size'),
                      validator: (v) => (v == null || double.tryParse(v) == null) ? 'Required' : null,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      controller: _unitController,
                      decoration: const InputDecoration(labelText: 'Unit (kg, g, L...)'),
                      validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: TextFormField(
                      controller: _mrpController,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(labelText: 'MRP (₹)'),
                      validator: (v) => (v == null || double.tryParse(v) == null) ? 'Required' : null,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      controller: _sellingPriceController,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(labelText: 'Selling Price (₹)'),
                      validator: (v) => (v == null || double.tryParse(v) == null) ? 'Required' : null,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _costPriceController,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(
                  labelText: 'Your cost price (₹)',
                  helperText: _isEditing
                      ? 'Never shown back to you (write-only) - leave blank to clear it, or re-enter to update'
                      : 'Never shown to customers - used for the free-delivery profit rule',
                  helperMaxLines: 2,
                ),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _imageUrlController,
                decoration: const InputDecoration(labelText: 'Image URL (optional)'),
              ),
              if (_isEditing) ...[
                const SizedBox(height: 8),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Available'),
                  value: _available,
                  onChanged: (value) => setState(() => _available = value),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(
          onPressed: _isSaving ? null : () => _save(),
          child: _isSaving
              ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}
