import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart';
import '../data/admin_products_repository.dart';
import 'admin_providers.dart';
import '../domain/selling_mode.dart';
import 'category_picker_sheet.dart';
import 'admin_variant_form_dialog.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminProductFormScreen extends ConsumerStatefulWidget {
  const AdminProductFormScreen({
    super.key,
    this.product,
    this.initialSellingMode = SellingMode.onlinePurchase,
  });

  /// Null means "create new product" - non-null means editing this one.
  final Product? product;

  /// Which selling mode this form OPENS on.
  ///
  /// Set by the Visit-to-Buy and Services screens so an item created from
  /// there is in the right mode from the first save. Creating it as an online
  /// listing and correcting it a moment later would leave a window in which a
  /// customer could buy something the shop cannot ship.
  final SellingMode initialSellingMode;

  @override
  ConsumerState<AdminProductFormScreen> createState() => _AdminProductFormScreenState();
}

class _AdminProductFormScreenState extends ConsumerState<AdminProductFormScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController;
  late final TextEditingController _brandController;
  // THE FIRST VARIANT, COLLECTED ON THE SAME SCREEN. A product with no variant
  // is not on any shelf, so a two-step flow left the merchant with something
  // they had created and could not see. See AdminProductsRepository.createProduct.
  late final TextEditingController _variantLabelController;
  late final TextEditingController _priceController;
  late final TextEditingController _mrpController;
  late final TextEditingController _stockController;
  int? _selectedCategoryId;
  String? _selectedCategoryName;

  /// HOW this shop sells the thing being created. Defaults to whatever screen
  /// opened this form, so Visit to Buy and Services land in the right mode.
  late SellingMode _sellingMode;
  PriceMode _priceMode = PriceMode.exact;
  OfflineStock _offlineStock = OfflineStock.available;
  final _priceMaxController = TextEditingController();
  final _serviceMinutesController = TextEditingController();

  SellingSetup get _sellingSetup => SellingSetup(
        selling: _sellingMode,
        price: _priceMode,
        priceMax: double.tryParse(_priceMaxController.text.trim()),
        stock: _offlineStock,
        serviceMinutes: int.tryParse(_serviceMinutesController.text.trim()),
      );
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
    _variantLabelController = TextEditingController();
    _priceController = TextEditingController();
    _mrpController = TextEditingController();
    _stockController = TextEditingController();
    _selectedCategoryId = widget.product?.category?.id;
    _selectedCategoryName = widget.product?.category?.name;
    _sellingMode = widget.initialSellingMode;
    if (!PriceMode.allowedFor(_sellingMode).contains(_priceMode)) {
      _priceMode = PriceMode.exact;
    }
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
    _variantLabelController.dispose();
    _priceController.dispose();
    _mrpController.dispose();
    _stockController.dispose();
    super.dispose();
  }

  /// A price is what turns a variant into a listing, so it is required rather
  /// than optional: ShopCatalog.list() declines to shelve anything priced at
  /// zero or null, which would put the merchant right back where they started -
  /// a product they made and cannot see.
  String? _validatePrice(String? value) {
    final text = (value ?? '').trim();
    if (text.isEmpty) return 'Set a selling price';
    final parsed = double.tryParse(text);
    if (parsed == null) return 'Enter a number';
    if (parsed <= 0) return 'Must be more than 0';
    return null;
  }

  String? _validateOptionalMrp(String? value) {
    final text = (value ?? '').trim();
    if (text.isEmpty) return null;
    final parsed = double.tryParse(text);
    if (parsed == null) return 'Enter a number';
    if (parsed < 0) return 'Cannot be negative';
    return null;
  }

  String? _validateStock(String? value) {
    final text = (value ?? '').trim();
    // Blank is "not counted yet" and becomes zero. A shop that has not counted
    // is not a shop with one of everything.
    if (text.isEmpty) return null;
    final parsed = int.tryParse(text);
    if (parsed == null) return 'Enter a whole number';
    if (parsed < 0) return 'Cannot be negative';
    return null;
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
          firstVariant: AdminFirstVariant(
            label: _variantLabelController.text.trim(),
            sellingPrice: double.parse(_priceController.text.trim()),
            mrp: _mrpController.text.trim().isEmpty
                ? null
                : double.tryParse(_mrpController.text.trim()),
            stock: _stockController.text.trim().isEmpty
                ? 0
                : int.tryParse(_stockController.text.trim()) ?? 0,
            selling: _sellingSetup,
          ),
        );
      }

      if (!mounted) return;
      // POPS ONLY AFTER THE SERVER SAID YES. The await above throws on any
      // non-2xx, so this line is unreachable on failure - which is what stops
      // the screen reporting a success the shelf does not have.
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
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop(false)), child: const Text('Cancel')),
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop(true)), child: const Text('Deactivate')),
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
    return Scaffold(
      appBar: AppBar(
        title: Text(_isEditing ? 'Edit Product' : 'Add Product'),
        actions: [
          if (_isEditing)
            IconButton(icon: const Icon(Icons.delete_outline), tooltip: 'Deactivate product', onPressed: hapticize(_deactivate)),
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
                // A SEARCHABLE PICKER, NOT A DROPDOWN OF EVERYTHING. The
                // dropdown listed the whole catalogue in id order, which is
                // thirty rows on a kirana and several thousand on a
                // marketplace - a phone merchant scrolled past Atta, Baby
                // Care and Beverages to reach Mobile Phones. The picker
                // searches server-side and puts this shop's own categories
                // first.
                _CategoryField(
                  categoryId: _selectedCategoryId,
                  categoryName: _selectedCategoryName,
                  onPick: () async {
                    final chosen = await CategoryPickerSheet.show(context,
                        selectedId: _selectedCategoryId);
                    if (chosen != null && mounted) {
                      setState(() {
                        _selectedCategoryId = chosen.id;
                        _selectedCategoryName = chosen.name;
                      });
                    }
                  },
                ),
                const SizedBox(height: 12),
                _SellingModeSection(
                  mode: _sellingMode,
                  priceMode: _priceMode,
                  stock: _offlineStock,
                  priceMaxController: _priceMaxController,
                  minutesController: _serviceMinutesController,
                  onModeChanged: (value) => setState(() {
                    _sellingMode = value;
                    if (!PriceMode.allowedFor(value).contains(_priceMode)) {
                      _priceMode = PriceMode.exact;
                    }
                  }),
                  onPriceModeChanged: (value) => setState(() => _priceMode = value),
                  onStockChanged: (value) => setState(() => _offlineStock = value),
                ),
                if (_isEditing)
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Active (visible to customers)'),
                    value: _isActive,
                    onChanged: hapticizeValue((value) => setState(() => _isActive = value)),
                  ),

                // THE FIRST VARIANT, ON THE SAME SCREEN, ONLY WHEN CREATING.
                // Editing an existing product leaves its variants alone - they
                // have their own section below.
                if (!_isEditing) ...[
                  const SizedBox(height: 24),
                  const Text('What you are selling',
                      style: TextStyle(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 4),
                  const Text(
                    'Every product needs one sellable form before it can go on '
                    'your shelf. You can add more later.',
                    style: TextStyle(fontSize: 12.5, color: AdminColors.textMuted),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _variantLabelController,
                    decoration: const InputDecoration(
                      labelText: 'Variant (optional)',
                      // Four trades in one hint, on purpose: this screen is not
                      // a kirana form.
                      hintText: 'e.g. 12 GB + 256 GB, Red silk, 1 kg, Half',
                    ),
                  ),
                  const SizedBox(height: 12),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: TextFormField(
                          controller: _priceController,
                          keyboardType:
                              const TextInputType.numberWithOptions(decimal: true),
                          decoration: const InputDecoration(
                              labelText: 'Selling price', prefixText: '₹ '),
                          validator: _validatePrice,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: TextFormField(
                          controller: _mrpController,
                          keyboardType:
                              const TextInputType.numberWithOptions(decimal: true),
                          decoration: const InputDecoration(
                              labelText: 'MRP (optional)', prefixText: '₹ '),
                          validator: _validateOptionalMrp,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _stockController,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Opening stock',
                      hintText: 'How many you have right now',
                    ),
                    validator: _validateStock,
                  ),
                ],
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
                    'Add more variants, prices and stock once this one is saved.',
                    style: TextStyle(color: AdminColors.textSecondary, fontSize: 12),
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
              onPressed: hapticize(() async {
                final saved = await showDialog<bool>(
                  context: context,
                  builder: (context) => AdminVariantFormDialog(
                      productId: product.id,
                      categoryName: product.category?.name),
                );
                if (saved == true) {
                  ref.invalidate(adminAllProductsProvider);
                  onVariantChanged();
                }
              }),
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
              style: TextStyle(color: AdminColors.danger, fontSize: 12),
            ),
          )
        else
          ...product.variants.map((variant) => Container(
                margin: const EdgeInsets.only(bottom: 8),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: AdminColors.surface, borderRadius: BorderRadius.circular(10)),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('${_formatQty(variant.quantity)} ${variant.unit ?? ''}',
                              style: const TextStyle(fontWeight: FontWeight.w600)),
                          Text(variant.hasPrice
                              ? '₹${variant.sellingPrice!.toStringAsFixed(0)}'
                                  '${variant.mrp != null ? ' (MRP ₹${variant.mrp!.toStringAsFixed(0)})' : ''}'
                              : 'No price (out of stock)'),
                          Text(
                            variant.available ? 'In stock' : 'Out of stock',
                            style: TextStyle(
                              fontSize: 12,
                              color: variant.available ? AdminColors.success : AdminColors.danger,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      tooltip: 'Edit variant',
                      onPressed: hapticize(() async {
                        final saved = await showDialog<bool>(
                          context: context,
                          builder: (context) => AdminVariantFormDialog(
                              productId: product.id,
                              variant: variant,
                              categoryName: product.category?.name),
                        );
                        if (saved == true) {
                          ref.invalidate(adminAllProductsProvider);
                          onVariantChanged();
                        }
                      }),
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

/// The category field: a tappable row that opens the searchable picker.
///
/// A ROW RATHER THAN A DROPDOWN because the list it opens is searchable and
/// grouped, which a DropdownButtonFormField cannot be, and because on a
/// catalogue of thousands a dropdown is a scroll with no way out.
class _CategoryField extends StatelessWidget {
  const _CategoryField({
    required this.categoryId,
    required this.categoryName,
    required this.onPick,
  });

  final int? categoryId;
  final String? categoryName;
  final Future<void> Function() onPick;

  @override
  Widget build(BuildContext context) {
    final chosen = categoryId != null;
    return InkWell(
      onTap: hapticize(onPick),
      borderRadius: BorderRadius.circular(8),
      child: InputDecorator(
        decoration: const InputDecoration(labelText: 'Category'),
        child: Row(
          children: [
            Expanded(
              child: Text(
                chosen ? (categoryName ?? 'Category #$categoryId') : 'Choose a category',
                style: TextStyle(
                  fontSize: 14,
                  color: chosen ? null : AdminColors.textSecondary,
                ),
              ),
            ),
            const Icon(Icons.search, size: 18, color: AdminColors.textSecondary),
          ],
        ),
      ),
    );
  }
}

/// How this shop sells the thing being created.
///
/// The controls that are meaningless for an online item - price mode, shelf
/// availability, duration - are ABSENT rather than disabled, and switching to
/// online moves an illegal price mode back rather than leaving an invalid pair
/// on screen for the server to reject.
class _SellingModeSection extends StatelessWidget {
  const _SellingModeSection({
    required this.mode,
    required this.priceMode,
    required this.stock,
    required this.priceMaxController,
    required this.minutesController,
    required this.onModeChanged,
    required this.onPriceModeChanged,
    required this.onStockChanged,
  });

  final SellingMode mode;
  final PriceMode priceMode;
  final OfflineStock stock;
  final TextEditingController priceMaxController;
  final TextEditingController minutesController;
  final ValueChanged<SellingMode> onModeChanged;
  final ValueChanged<PriceMode> onPriceModeChanged;
  final ValueChanged<OfflineStock> onStockChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('How you sell this',
            style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
        const SizedBox(height: 8),
        SegmentedButton<SellingMode>(
          segments: SellingMode.values
              .map((m) => ButtonSegment<SellingMode>(
                    value: m,
                    label: Text(m.label, style: const TextStyle(fontSize: 11.5)),
                  ))
              .toList(),
          selected: {mode},
          showSelectedIcon: false,
          onSelectionChanged: (chosen) => onModeChanged(chosen.first),
        ),
        const SizedBox(height: 6),
        Text(mode.explanation,
            style: const TextStyle(fontSize: 11.5, color: AdminColors.textSecondary)),
        if (!mode.isOnline) ...[
          const SizedBox(height: 12),
          DropdownButtonFormField<PriceMode>(
            initialValue: priceMode,
            decoration: const InputDecoration(labelText: 'How the price is shown'),
            items: PriceMode.allowedFor(mode)
                .map((m) => DropdownMenuItem(value: m, child: Text(m.label)))
                .toList(),
            onChanged: (m) => onPriceModeChanged(m ?? PriceMode.exact),
          ),
          if (priceMode == PriceMode.range) ...[
            const SizedBox(height: 12),
            TextFormField(
              controller: priceMaxController,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(
                labelText: 'Top of the range (₹)',
                helperText: 'The price above is the bottom of the range',
              ),
            ),
          ],
          const SizedBox(height: 12),
          DropdownButtonFormField<OfflineStock>(
            initialValue: stock,
            decoration: const InputDecoration(labelText: 'At the shop'),
            items: OfflineStock.values
                .map((v) => DropdownMenuItem(value: v, child: Text(v.label)))
                .toList(),
            onChanged: (v) => onStockChanged(v ?? OfflineStock.available),
          ),
        ],
        if (mode == SellingMode.serviceAtShop) ...[
          const SizedBox(height: 12),
          TextFormField(
            controller: minutesController,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'How long it takes (minutes)',
              helperText: 'Optional - helps customers plan their visit',
            ),
          ),
        ],
      ],
    );
  }
}
