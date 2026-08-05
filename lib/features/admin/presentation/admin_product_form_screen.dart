import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart';
import 'admin_providers.dart';
import 'admin_variant_form_dialog.dart';

class AdminProductFormScreen extends ConsumerStatefulWidget {
  const AdminProductFormScreen({super.key, this.product});

  /// Null means "create new product" - non-null means editing this one.
  final Product? product;

  @override
  ConsumerState<AdminProductFormScreen> createState() => _AdminProductFormScreenState();
}

class _AdminProductFormScreenState extends ConsumerState<AdminProductFormScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController;
  late final TextEditingController _brandController;
  int? _selectedCategoryId;
  late bool _isActive;
  bool _isSaving = false;

  // Starts as widget.product, but is refetched after every variant add/edit
  // so this screen actually reflects the change immediately - the object
  // passed in via the constructor is just a point-in-time snapshot and never
  // updates itself as variants are added elsewhere.
  Product? _currentProduct;

  bool get _isEditing => widget.product != null;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.product?.name ?? '');
    _brandController = TextEditingController(text: widget.product?.brand ?? '');
    _selectedCategoryId = widget.product?.category?.id;
    _isActive = widget.product?.active ?? true;
    _currentProduct = widget.product;
  }

  Future<void> _refreshCurrentProduct() async {
    if (widget.product == null) return;
    try {
      final all = await ref.read(adminProductsRepositoryProvider).getAllForAdmin();
      Product? refreshed;
      for (final p in all) {
        if (p.id == widget.product!.id) {
          refreshed = p;
          break;
        }
      }
      if (refreshed != null && mounted) {
        setState(() => _currentProduct = refreshed);
      }
    } catch (_) {
      // Non-fatal - the variant save itself already succeeded (or failed and
      // was reported separately); this is just the display refresh.
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _brandController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedCategoryId == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Please select a category')));
      return;
    }

    setState(() => _isSaving = true);

    try {
      final repository = ref.read(adminProductsRepositoryProvider);

      if (_isEditing) {
        await repository.updateProduct(
          productId: widget.product!.id,
          name: _nameController.text.trim(),
          brand: _brandController.text.trim().isEmpty ? null : _brandController.text.trim(),
          categoryId: _selectedCategoryId!,
          active: _isActive,
        );
      } else {
        await repository.createProduct(
          name: _nameController.text.trim(),
          brand: _brandController.text.trim().isEmpty ? null : _brandController.text.trim(),
          categoryId: _selectedCategoryId!,
        );
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

  Future<void> _deactivate() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Deactivate this product?'),
        content: const Text('It will stop showing up to customers. This can be reversed by editing it again.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Deactivate')),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await ref.read(adminProductsRepositoryProvider).deactivateProduct(widget.product!.id);
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
    final categoriesAsync = ref.watch(adminCategoriesProvider);

    return Scaffold(
      appBar: AppBar(
        title: Text(_isEditing ? 'Edit Product' : 'Add Product'),
        actions: [
          if (_isEditing)
            IconButton(icon: const Icon(Icons.delete_outline), tooltip: 'Deactivate product', onPressed: _deactivate),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextFormField(
                  controller: _nameController,
                  decoration: const InputDecoration(labelText: 'Product name'),
                  validator: (value) => (value == null || value.trim().isEmpty) ? 'Name is required' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _brandController,
                  decoration: const InputDecoration(labelText: 'Brand (optional)'),
                ),
                const SizedBox(height: 12),
                categoriesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, s) => Text(extractErrorMessage(e), textAlign: TextAlign.center),
                  data: (categories) => DropdownButtonFormField<int>(
                    initialValue: _selectedCategoryId,
                    decoration: const InputDecoration(labelText: 'Category'),
                    items: categories
                        .map((c) => DropdownMenuItem(value: c.id, child: Text(c.name)))
                        .toList(),
                    onChanged: (value) => setState(() => _selectedCategoryId = value),
                  ),
                ),
                if (_isEditing)
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Active (visible to customers)'),
                    value: _isActive,
                    onChanged: (value) => setState(() => _isActive = value),
                  ),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: _isSaving ? null : _save,
                  child: _isSaving
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : Text(_isEditing ? 'Save Changes' : 'Create Product'),
                ),

                if (_isEditing) ...[
                  const SizedBox(height: 32),
                  _VariantsSection(
                    product: _currentProduct ?? widget.product!,
                    onVariantChanged: _refreshCurrentProduct,
                  ),
                ] else ...[
                  const SizedBox(height: 16),
                  const Text(
                    'Save the product first, then add its variants (pack sizes, prices, stock).',
                    style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
                    textAlign: TextAlign.center,
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _VariantsSection extends ConsumerWidget {
  const _VariantsSection({required this.product, required this.onVariantChanged});

  final Product product;
  final VoidCallback onVariantChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('Variants', style: Theme.of(context).textTheme.titleMedium),
            TextButton.icon(
              onPressed: () async {
                final saved = await showDialog<bool>(
                  context: context,
                  builder: (context) => AdminVariantFormDialog(productId: product.id),
                );
                if (saved == true) {
                  ref.invalidate(adminAllProductsProvider);
                  onVariantChanged();
                }
              },
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Add Variant'),
            ),
          ],
        ),
        if (product.variants.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Text(
              'No variants yet - this product cannot be sold until it has at least one.',
              style: TextStyle(color: AppColors.error, fontSize: 12),
            ),
          )
        else
          ...product.variants.map((variant) => Container(
                margin: const EdgeInsets.only(bottom: 8),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(10)),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('${_formatQty(variant.quantity)} ${variant.unit ?? ''}',
                              style: const TextStyle(fontWeight: FontWeight.w600)),
                          Text('₹${variant.sellingPrice.toStringAsFixed(0)}'
                              '${variant.mrp != null ? ' (MRP ₹${variant.mrp!.toStringAsFixed(0)})' : ''}'),
                          Text(
                            variant.available ? 'In stock' : 'Out of stock',
                            style: TextStyle(
                              fontSize: 12,
                              color: variant.available ? AppColors.success : AppColors.error,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      tooltip: 'Edit variant',
                      onPressed: () async {
                        final saved = await showDialog<bool>(
                          context: context,
                          builder: (context) => AdminVariantFormDialog(productId: product.id, variant: variant),
                        );
                        if (saved == true) {
                          ref.invalidate(adminAllProductsProvider);
                          onVariantChanged();
                        }
                      },
                    ),
                  ],
                ),
              )),
      ],
    );
  }

  String _formatQty(double? quantity) {
    if (quantity == null) return '';
    return quantity == quantity.roundToDouble() ? quantity.toStringAsFixed(0) : quantity.toStringAsFixed(1);
  }
}
