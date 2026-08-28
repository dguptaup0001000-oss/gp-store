import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import '../../products/domain/product_models.dart';
import '../data/admin_products_repository.dart';
import '../domain/variant_save_action.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminVariantFormDialog extends ConsumerStatefulWidget {
  const AdminVariantFormDialog(
      {super.key, required this.productId, this.variant});

  final int productId;

  /// Null means "add new variant" - non-null means editing this one.
  final ProductVariant? variant;

  @override
  ConsumerState<AdminVariantFormDialog> createState() =>
      _AdminVariantFormDialogState();
}

class _AdminVariantFormDialogState
    extends ConsumerState<AdminVariantFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _quantityController;
  late final TextEditingController _unitController;
  late final TextEditingController _mrpController;
  late final TextEditingController _sellingPriceController;
  late final TextEditingController _costPriceController;
  late bool _available;
  bool _isSaving = false;
  bool _isUploadingImage = false;

  /// The photos this variant should end up with, in order. First is primary.
  ///
  /// THE WHOLE LIST IS THE UNIT, matching the server. The screen edits this
  /// list and sends it; there is no add-one or delete-one call, because the
  /// first entry is the primary photo and order is therefore meaning - three
  /// separate operations is three chances for this screen's order and the
  /// server's to disagree about which photo the customer sees first.
  List<String> _images = const [];

  /// True once the list differs from what the server had, so an ordinary
  /// price edit does not rewrite the photo rows for nothing.
  bool _imagesChanged = false;

  /// Set after the first successful create so a retry after a later photo
  /// failure updates that variant instead of inserting a duplicate.
  int? _createdVariantId;

  bool get _isEditing => widget.variant != null;

  @override
  void initState() {
    super.initState();
    final v = widget.variant;
    _quantityController =
        TextEditingController(text: v?.quantity?.toString() ?? '');
    _unitController = TextEditingController(text: v?.unit ?? '');
    _mrpController = TextEditingController(text: v?.mrp?.toString() ?? '');
    _sellingPriceController =
        TextEditingController(text: v?.sellingPrice.toString() ?? '');
    // costPrice is deliberately never populated for edit - the backend never
    // returns it (WRITE_ONLY, see ProductVariant.costPrice), so there's
    // nothing to prefill. Leaving it blank means "no change" is NOT what
    // happens here though - see the save-time warning below.
    _costPriceController = TextEditingController();
    _available = v?.available ?? true;

    // Seeded from the variant's existing single thumbnail so an old
    // one-image variant opens showing the photo it already has, rather than
    // an empty strip that reads as "this product has no picture". The real
    // list is then loaded from the server below and replaces it.
    _images = (v?.imageUrl != null && v!.imageUrl!.isNotEmpty)
        ? [v.imageUrl!]
        : const [];
    if (_isEditing) {
      _loadExistingImages();
    }
  }

  /// Loads the variant's real gallery.
  ///
  /// Failure is deliberately quiet: the dialog is already usable with the
  /// single thumbnail seeded above, and an error banner over a price form
  /// because a photo list did not load would be noise. The admin can still
  /// add photos; they just start from what the variant already showed.
  Future<void> _loadExistingImages() async {
    try {
      final urls = await ref
          .read(adminProductsRepositoryProvider)
          .getVariantImages(widget.variant!.id);
      if (mounted && urls.isNotEmpty) {
        setState(() => _images = urls);
      }
    } catch (_) {
      // Keep the seeded thumbnail.
    }
  }

  @override
  void dispose() {
    _quantityController.dispose();
    _unitController.dispose();
    _mrpController.dispose();
    _sellingPriceController.dispose();
    _costPriceController.dispose();
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
      // The primary photo IS the variant's imageUrl. Everything that is not
      // the detail screen - listings, cart lines, order items - reads that one
      // field and knows nothing about galleries, so keeping it equal to the
      // first photo is what makes the new photos show up in all of them
      // without a single client change.
      final imageUrl = _images.isEmpty ? null : _images.first;
      final action = variantSaveAction(
        isEditing: _isEditing,
        createdVariantId: _createdVariantId,
      );

      if (action == VariantSaveAction.update && _isEditing) {
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
        if (_imagesChanged) {
          await repository.setVariantImages(widget.variant!.id, _images);
        }
      } else if (action == VariantSaveAction.update) {
        await repository.updateVariant(
          variantId: _createdVariantId!,
          quantity: quantity,
          unit: _unitController.text.trim(),
          imageUrl: imageUrl,
          mrp: mrp,
          sellingPrice: sellingPrice,
          costPrice: costPrice,
          available: _available,
          allowBelowCost: allowBelowCost,
        );
        if (_images.length > 1 || _imagesChanged) {
          await repository.setVariantImages(_createdVariantId!, _images);
        }
      } else {
        final newVariantId = await repository.createVariant(
          productId: widget.productId,
          quantity: quantity,
          unit: _unitController.text.trim(),
          imageUrl: imageUrl,
          mrp: mrp,
          sellingPrice: sellingPrice,
          costPrice: costPrice,
          allowBelowCost: allowBelowCost,
        );
        _createdVariantId = newVariantId;

        // Photos go in a second call because they live in their own table and
        // need a variant to point at. Only when there is more than the
        // primary - a single photo is already saved as imageUrl above, and
        // writing one row to say the same thing would be a round trip for
        // nothing on the common case.
        if (_images.length > 1) {
          await repository.setVariantImages(newVariantId, _images);
        }
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
            content: const Text(
                'This means you\'d lose money on every sale. Save anyway?'),
            actions: [
              TextButton(
                  onPressed: hapticize(() => Navigator.of(context).pop(false)),
                  child: const Text('Cancel')),
              TextButton(
                  onPressed: hapticize(() => Navigator.of(context).pop(true)),
                  child: const Text('Save anyway')),
            ],
          ),
        );
        if (confirmed == true) {
          await _save(allowBelowCost: true);
          return;
        }
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
              content: Text(
                  "Couldn't save variant - please check the values and try again")),
        );
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  Future<void> _addPhotos() async {
    final remaining = AdminProductsRepository.maxVariantImages - _images.length;
    if (remaining <= 0) {
      return;
    }

    setState(() => _isUploadingImage = true);
    try {
      final result = await ref
          .read(adminProductsRepositoryProvider)
          .pickAndUploadVariantImages(
        remaining: remaining,
        ownerId: widget.variant?.id ?? _createdVariantId,
      );

      if (!mounted) return;

      if (result.urls.isNotEmpty) {
        setState(() {
          // Duplicates dropped here as well as on the server. The same photo
          // picked twice is an ordinary slip at a picker, and two identical
          // thumbnails reads as a broken shop rather than a mis-tap.
          _images = [
            ..._images,
            ...result.urls.where((u) => !_images.contains(u)),
          ];
          _imagesChanged = true;
        });
      }

      // SAID OUT LOUD, not silently ignored. Picking eight when three fit
      // means five taps did nothing, and an admin who is not told assumes the
      // photos are there.
      if (result.skipped > 0) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(
              '${result.skipped} photo${result.skipped == 1 ? '' : 's'} not added - '
              'a variant can have at most ${AdminProductsRepository.maxVariantImages}.'),
        ));
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isUploadingImage = false);
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
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(labelText: 'Pack size'),
                      validator: (v) =>
                          (v == null || double.tryParse(v) == null)
                              ? 'Required'
                              : null,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      controller: _unitController,
                      decoration: const InputDecoration(
                          labelText: 'Unit (kg, g, L...)'),
                      validator: (v) =>
                          (v == null || v.trim().isEmpty) ? 'Required' : null,
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
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(labelText: 'MRP (₹)'),
                      validator: (v) =>
                          (v == null || double.tryParse(v) == null)
                              ? 'Required'
                              : null,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      controller: _sellingPriceController,
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      decoration:
                          const InputDecoration(labelText: 'Selling Price (₹)'),
                      validator: (v) =>
                          (v == null || double.tryParse(v) == null)
                              ? 'Required'
                              : null,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _costPriceController,
                keyboardType:
                    const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(
                  labelText: 'Your cost price (₹)',
                  helperText: _isEditing
                      ? 'Never shown back to you (write-only) - leave blank to clear it, or re-enter to update'
                      : 'Never shown to customers - used for the free-delivery profit rule',
                  helperMaxLines: 2,
                ),
              ),
              const SizedBox(height: 16),

              // PHOTOS. The counter is part of the label rather than a
              // separate line because "how many can I still add" is the only
              // question this section raises, and answering it in the heading
              // costs no vertical space in a dialog that is already tall.
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                      'Product photos (${_images.length}/${AdminProductsRepository.maxVariantImages})',
                      style: Theme.of(context).textTheme.titleSmall),
                  TextButton.icon(
                    onPressed: (_isUploadingImage ||
                            _images.length >=
                                AdminProductsRepository.maxVariantImages)
                        ? null
                        : hapticize(_addPhotos),
                    icon: _isUploadingImage
                        ? const SizedBox(
                            height: 16,
                            width: 16,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.add_photo_alternate_outlined,
                            size: 20),
                    label:
                        Text(_isUploadingImage ? 'Uploading...' : 'Add photos'),
                  ),
                ],
              ),

              if (_images.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    'No photos yet. The first photo you add becomes the main one '
                    'customers see on product cards.',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                )
              else
                SizedBox(
                  height: 104,
                  child: ListView.separated(
                    scrollDirection: Axis.horizontal,
                    itemCount: _images.length,
                    separatorBuilder: (_, __) => const SizedBox(width: 8),
                    itemBuilder: (context, index) => _PhotoThumb(
                      url: _images[index],
                      isPrimary: index == 0,
                      onRemove: hapticize(() => setState(() {
                            _images = [..._images]..removeAt(index);
                            _imagesChanged = true;
                          })),
                      // REORDERING, REDUCED TO THE ONE MOVE THAT MATTERS.
                      // Drag-to-reorder inside a horizontal strip inside a
                      // scrolling dialog is fiddly on a phone and the only
                      // position that carries meaning is the first one. So:
                      // tap a photo to make it the main one.
                      onMakePrimary: index == 0
                          ? null
                          : hapticize(() => setState(() {
                                final moved = [..._images];
                                moved.insert(0, moved.removeAt(index));
                                _images = moved;
                                _imagesChanged = true;
                              })),
                    ),
                  ),
                ),
              const SizedBox(height: 4),

              if (_isEditing) ...[
                const SizedBox(height: 8),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Available'),
                  value: _available,
                  onChanged: hapticizeValue(
                      (value) => setState(() => _available = value)),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: hapticize(() => Navigator.of(context).pop(false)),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: _isSaving ? null : () => _save(),
          child: _isSaving
              ? const SizedBox(
                  height: 16,
                  width: 16,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}

/// One photo in the strip: the picture, whether it is the main one, and the
/// two things you can do to it.
class _PhotoThumb extends StatelessWidget {
  const _PhotoThumb({
    required this.url,
    required this.isPrimary,
    required this.onRemove,
    required this.onMakePrimary,
  });

  final String url;
  final bool isPrimary;
  final VoidCallback onRemove;

  /// Null for the photo that already is the main one.
  final VoidCallback? onMakePrimary;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SizedBox(
      width: 88,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          GestureDetector(
            onTap: onMakePrimary,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: Container(
                decoration: BoxDecoration(
                  border: isPrimary
                      ? Border.all(color: theme.colorScheme.primary, width: 2)
                      : null,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Image.network(
                  url,
                  height: 88,
                  width: 88,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => Container(
                    height: 88,
                    width: 88,
                    color: theme.colorScheme.surfaceContainerHighest,
                    child: const Icon(Icons.broken_image_outlined, size: 20),
                  ),
                ),
              ),
            ),
          ),
          if (isPrimary)
            Positioned(
              bottom: 0,
              left: 0,
              right: 0,
              child: Container(
                color: theme.colorScheme.primary,
                padding: const EdgeInsets.symmetric(vertical: 2),
                child: Text(
                  'MAIN',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                    color: theme.colorScheme.onPrimary,
                  ),
                ),
              ),
            ),
          Positioned(
            top: -6,
            right: -6,
            child: IconButton(
              icon: const Icon(Icons.cancel, size: 20),
              tooltip: 'Remove photo',
              onPressed: onRemove,
            ),
          ),
        ],
      ),
    );
  }
}
