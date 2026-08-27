import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart' show DiscountType;
import '../domain/admin_coupon_models.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminCouponFormDialog extends ConsumerStatefulWidget {
  const AdminCouponFormDialog({super.key, this.coupon});

  /// Null means "create new coupon" - non-null means editing this one.
  final AdminCoupon? coupon;

  @override
  ConsumerState<AdminCouponFormDialog> createState() => _AdminCouponFormDialogState();
}

class _AdminCouponFormDialogState extends ConsumerState<AdminCouponFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _codeController;
  late final TextEditingController _valueController;
  late final TextEditingController _maxDiscountController;
  late final TextEditingController _minOrderController;
  late final TextEditingController _usageLimitController;
  late DiscountType _discountType;
  DateTime? _expiryDate;
  late bool _active;
  bool _isSaving = false;

  bool get _isEditing => widget.coupon != null;

  @override
  void initState() {
    super.initState();
    final c = widget.coupon;
    _codeController = TextEditingController(text: c?.couponCode ?? '');
    _valueController = TextEditingController(text: c?.discountValue.toString() ?? '');
    _maxDiscountController = TextEditingController(text: c?.maxDiscountAmount?.toString() ?? '');
    _minOrderController = TextEditingController(text: c?.minimumOrderAmount?.toString() ?? '');
    _usageLimitController = TextEditingController(text: c?.usageLimit?.toString() ?? '');
    _discountType = c?.discountType ?? DiscountType.percentage;
    _expiryDate = c?.expiryDate != null ? DateTime.tryParse(c!.expiryDate!) : null;
    _active = c?.active ?? true;
  }

  @override
  void dispose() {
    _codeController.dispose();
    _valueController.dispose();
    _maxDiscountController.dispose();
    _minOrderController.dispose();
    _usageLimitController.dispose();
    super.dispose();
  }

  Future<void> _pickExpiryDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _expiryDate ?? DateTime.now().add(const Duration(days: 30)),
      firstDate: DateTime.now(),
      lastDate: DateTime.now().add(const Duration(days: 365 * 2)),
    );
    if (picked != null) setState(() => _expiryDate = picked);
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSaving = true);

    try {
      final repository = ref.read(adminProductsRepositoryProvider);
      final discountValue = double.parse(_valueController.text.trim());
      final maxDiscount = _maxDiscountController.text.trim().isEmpty
          ? null
          : double.parse(_maxDiscountController.text.trim());
      final minOrder = _minOrderController.text.trim().isEmpty
          ? null
          : double.parse(_minOrderController.text.trim());
      final usageLimit = _usageLimitController.text.trim().isEmpty
          ? null
          : int.parse(_usageLimitController.text.trim());
      final expiryDateStr = _expiryDate == null
          ? null
          : '${_expiryDate!.year.toString().padLeft(4, '0')}-${_expiryDate!.month.toString().padLeft(2, '0')}-${_expiryDate!.day.toString().padLeft(2, '0')}';

      if (_isEditing) {
        await repository.updateCoupon(
          couponId: widget.coupon!.id,
          discountType: _discountType,
          discountValue: discountValue,
          maxDiscountAmount: maxDiscount,
          minimumOrderAmount: minOrder,
          expiryDate: expiryDateStr,
          usageLimit: usageLimit,
          active: _active,
        );
      } else {
        await repository.createCoupon(
          couponCode: _codeController.text.trim(),
          discountType: _discountType,
          discountValue: discountValue,
          maxDiscountAmount: maxDiscount,
          minimumOrderAmount: minOrder,
          expiryDate: expiryDateStr,
          usageLimit: usageLimit,
        );
      }

      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Couldn't save coupon - check the values and try again")),
      );
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  Future<void> _deactivate() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Deactivate this coupon?'),
        content: const Text('Customers will no longer be able to use it.'),
        actions: [
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop(false)), child: const Text('Cancel')),
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop(true)), child: const Text('Deactivate')),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await ref.read(adminProductsRepositoryProvider).deactivateCoupon(widget.coupon!.id);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isEditing ? 'Edit Coupon' : 'Add Coupon'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _codeController,
                textCapitalization: TextCapitalization.characters,
                enabled: !_isEditing, // code is immutable after creation - see CouponService.update
                decoration: InputDecoration(
                  labelText: 'Coupon code',
                  helperText: _isEditing ? 'Code cannot be changed after creation' : null,
                ),
                validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<DiscountType>(
                initialValue: _discountType,
                decoration: const InputDecoration(labelText: 'Discount type'),
                items: const [
                  DropdownMenuItem(value: DiscountType.percentage, child: Text('Percentage off')),
                  DropdownMenuItem(value: DiscountType.flat, child: Text('Flat amount off cart')),
                  DropdownMenuItem(
                    value: DiscountType.deliveryFlat,
                    child: Text('Up to ₹ off delivery'),
                  ),
                ],
                onChanged: hapticizeValue((value) => setState(() => _discountType = value!)),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _valueController,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(
                  labelText: _discountType == DiscountType.percentage
                      ? 'Discount (%)'
                      : _discountType == DiscountType.deliveryFlat
                          ? 'Max delivery discount (₹)'
                          : 'Discount (₹)',
                  helperText: _discountType == DiscountType.deliveryFlat
                      ? 'Example: 10 makes a ₹10 fee free, and a ₹20 fee become ₹10'
                      : null,
                ),
                validator: (v) => (v == null || double.tryParse(v) == null) ? 'Required' : null,
              ),
              if (_discountType == DiscountType.percentage) ...[
                const SizedBox(height: 12),
                TextFormField(
                  controller: _maxDiscountController,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(labelText: 'Max discount cap (₹, optional)'),
                ),
              ],
              const SizedBox(height: 12),
              TextFormField(
                controller: _minOrderController,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Minimum order amount (optional)'),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _usageLimitController,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Usage limit (optional, blank = unlimited)'),
              ),
              const SizedBox(height: 12),
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(_expiryDate == null ? 'No expiry date' : 'Expires: ${_expiryDate!.toLocal()}'.split(' ')[0]),
                trailing: const Icon(Icons.calendar_today_outlined, size: 18),
                onTap: hapticize(_pickExpiryDate),
              ),
              if (_isEditing)
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Active'),
                  value: _active,
                  onChanged: hapticizeValue((value) => setState(() => _active = value)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        if (_isEditing)
          TextButton(
            onPressed: hapticize(_deactivate),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('Deactivate'),
          ),
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
