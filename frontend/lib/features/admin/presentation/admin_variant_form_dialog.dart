import 'package:flutter/material.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/images/gp_network_image.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import '../../products/domain/product_models.dart';
import '../data/admin_products_repository.dart';
import '../domain/selling_mode.dart';
import '../domain/variant_save_action.dart';
import '../domain/variant_attribute.dart';
import '../../../core/api/error_messages.dart'
    show apiStatusOf, meansEndpointMissing;
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminVariantFormDialog extends ConsumerStatefulWidget {
  const AdminVariantFormDialog(
      {super.key,
      required this.productId,
      this.variant,
      this.categoryName});

  final int productId;

  /// Only used to choose which attribute names the form OPENS with. Never a
  /// constraint: the merchant can rename or remove any of them, and a trade
  /// nobody anticipated simply types its own.
  final String? categoryName;

  /// Null means "add new variant" - non-null means editing this one.
  final ProductVariant? variant;

  @override
  ConsumerState<AdminVariantFormDialog> createState() =>
      _AdminVariantFormDialogState();
}

class _AdminVariantFormDialogState
    extends ConsumerState<AdminVariantFormDialog> {
  final _formKey = GlobalKey<FormState>();

  /// What the merchant calls this variant in one line - "8 GB + 128 GB",
  /// "Red, pure silk", "1 kg", "Half plate". Optional.
  late final TextEditingController _labelController;

  /// The variant's details as name/value pairs. THE FIELD THAT REPLACED
  /// "Pack size" and "Unit (kg, g, L...)", which asked every trade the
  /// grocer's question and left a phone merchant typing "8" under "Pack size".
  final List<_AttributeRow> _attributes = [];

  /// Matches the backend's own ceiling (ShopVariantEditing.MAX_ATTRIBUTES), so
  /// the form cannot build a request the server will refuse.
  static const int _maxAttributes = 20;
  late final TextEditingController _mrpController;
  late final TextEditingController _sellingPriceController;
  late final TextEditingController _costPriceController;
  late bool _available;

  /// HOW this shop sells this one thing. Defaults to online, which is what
  /// every listing was before modes existed, so a kirana never meets these
  /// controls as a decision they have to make.
  SellingMode _selling = SellingMode.onlinePurchase;
  PriceMode _priceMode = PriceMode.exact;
  OfflineStock _stock = OfflineStock.available;
  late final TextEditingController _priceMaxController;
  late final TextEditingController _serviceMinutesController;

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
    _labelController = TextEditingController(text: v?.unit ?? '');
    _mrpController = TextEditingController(text: v?.mrp?.toString() ?? '');
    _sellingPriceController =
        TextEditingController(text: v?.sellingPrice?.toString() ?? '');
    // costPrice is deliberately never populated for edit - the backend never
    // returns it (WRITE_ONLY, see ProductVariant.costPrice), so there's
    // nothing to prefill. Leaving it blank means "no change" is NOT what
    // happens here though - see the save-time warning below.
    _costPriceController = TextEditingController();
    _priceMaxController = TextEditingController();
    _serviceMinutesController = TextEditingController();
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
      _loadExistingAttributes();
    } else {
      _seedAttributesFromTemplate();
    }
  }

  /// Opens the form with the names this trade usually needs, so a phone shop
  /// is not asked to invent the word "RAM" from a blank screen. Values are
  /// empty and every row can be renamed or removed.
  void _seedAttributesFromTemplate() {
    for (final name in VariantAttributeTemplates.forCategory(widget.categoryName)) {
      _attributes.add(_AttributeRow(name: name, value: ''));
    }
  }

  /// The attributes this variant already carries.
  ///
  /// Quiet on failure for the same reason the photo load is: the form is
  /// usable without them, and an error banner over a price screen because a
  /// details list did not load would be noise. A variant that has none - every
  /// variant created before this feature - falls back to the trade template so
  /// the merchant still has somewhere to type.
  Future<void> _loadExistingAttributes() async {
    try {
      final loaded = await ref
          .read(adminProductsRepositoryProvider)
          .getVariantAttributes(widget.variant!.id);
      if (!mounted) return;
      setState(() {
        _attributes
          ..clear()
          ..addAll(loaded.map((a) => _AttributeRow(name: a.name, value: a.value)));
        if (_attributes.isEmpty) _seedAttributesFromTemplate();
      });
    } catch (_) {
      if (!mounted) return;
      if (_attributes.isEmpty) setState(_seedAttributesFromTemplate);
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
    _labelController.dispose();
    for (final row in _attributes) {
      row.dispose();
    }
    _mrpController.dispose();
    _sellingPriceController.dispose();
    _priceMaxController.dispose();
    _serviceMinutesController.dispose();
    _costPriceController.dispose();
    super.dispose();
  }

  /// HOW THIS SHOP SELLS THIS ONE THING.
  ///
  /// Not a separate screen and not a separate kind of product: a jeweller, a
  /// barber and a kirana all fill in the same form, and this is the field
  /// where they differ. A shop that only ever sells online never touches it -
  /// the default is what every listing already was.
  Widget _sellingModeSection() {
    final allowedPrices = PriceMode.allowedFor(_selling);
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
          selected: {_selling},
          showSelectedIcon: false,
          onSelectionChanged: (chosen) {
            setState(() {
              _selling = chosen.first;
              // An online item must be exactly priced, so switching to online
              // puts the price mode back where the rule allows rather than
              // leaving an invalid pair on screen for the server to reject.
              if (!PriceMode.allowedFor(_selling).contains(_priceMode)) {
                _priceMode = PriceMode.exact;
              }
            });
          },
        ),
        const SizedBox(height: 6),
        Text(_selling.explanation,
            style: const TextStyle(fontSize: 11.5, color: AppColors.textSecondary)),

        // Everything below is meaningless for an online listing - it is
        // exactly priced, it is in stock or it is not, and it takes no time.
        if (!_selling.isOnline) ...[
          const SizedBox(height: 12),
          DropdownButtonFormField<PriceMode>(
            initialValue: _priceMode,
            decoration: const InputDecoration(labelText: 'How the price is shown'),
            items: allowedPrices
                .map((m) => DropdownMenuItem(value: m, child: Text(m.label)))
                .toList(),
            onChanged: (m) => setState(() => _priceMode = m ?? PriceMode.exact),
          ),
          if (_priceMode == PriceMode.range) ...[
            const SizedBox(height: 12),
            TextFormField(
              controller: _priceMaxController,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(
                labelText: 'Top of the range (₹)',
                helperText: 'The selling price above is the bottom of the range',
              ),
            ),
          ],
          const SizedBox(height: 12),
          DropdownButtonFormField<OfflineStock>(
            initialValue: _stock,
            decoration: const InputDecoration(labelText: 'At the shop'),
            items: OfflineStock.values
                .map((v) => DropdownMenuItem(value: v, child: Text(v.label)))
                .toList(),
            onChanged: (v) => setState(() => _stock = v ?? OfflineStock.available),
          ),
        ],
        if (_selling == SellingMode.serviceAtShop) ...[
          const SizedBox(height: 12),
          TextFormField(
            controller: _serviceMinutesController,
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

  /// What the merchant typed about selling, as one value.
  SellingSetup get _sellingSetup => SellingSetup(
        selling: _selling,
        price: _priceMode,
        priceMax: double.tryParse(_priceMaxController.text.trim()),
        stock: _stock,
        serviceMinutes: int.tryParse(_serviceMinutesController.text.trim()),
      );

  Future<void> _save({bool allowBelowCost = false}) async {
    if (!_formKey.currentState!.validate()) return;

    // Told beside the field rather than after a round trip. The server checks
    // the same rules again and is the one that binds.
    final setup = _sellingSetup;
    final wrong = setup.problem(
        sellingPrice: double.tryParse(_sellingPriceController.text.trim()));
    if (wrong != null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(wrong)));
      return;
    }

    setState(() => _isSaving = true);

    try {
      final repository = ref.read(adminProductsRepositoryProvider);
      final attributes = _collectAttributes();
      // GROCERY COMPATIBILITY, WITHOUT GROCERY ASSUMPTIONS. A kirana still
      // types "Pack size" and "Unit" - they are simply two attribute names
      // among many now - and those two still fill the catalogue's numeric
      // quantity/unit columns, which is what weighs a basket for delivery.
      // A phone shop fills neither and nothing breaks.
      final quantity = _numberNamed(attributes, 'pack size') ??
          _numberNamed(attributes, 'volume') ??
          _numberNamed(attributes, 'quantity');
      final unit = _valueNamed(attributes, 'unit');
      final label = _labelController.text.trim().isEmpty
          ? VariantAttribute.describe(attributes)
          : _labelController.text.trim();
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
          label: label,
          quantity: quantity,
          unit: unit,
          attributes: attributes,
          imageUrl: imageUrl,
          mrp: mrp,
          sellingPrice: sellingPrice,
          costPrice: costPrice,
          available: _available,
          allowBelowCost: allowBelowCost,
          selling: setup,
        );
        if (_imagesChanged) {
          await repository.setVariantImages(widget.variant!.id, _images);
        }
      } else if (action == VariantSaveAction.update) {
        await repository.updateVariant(
          variantId: _createdVariantId!,
          label: label,
          quantity: quantity,
          unit: unit,
          attributes: attributes,
          imageUrl: imageUrl,
          mrp: mrp,
          sellingPrice: sellingPrice,
          costPrice: costPrice,
          available: _available,
          allowBelowCost: allowBelowCost,
          selling: setup,
        );
        if (_images.length > 1 || _imagesChanged) {
          await repository.setVariantImages(_createdVariantId!, _images);
        }
      } else {
        final newVariantId = await repository.createVariant(
          productId: widget.productId,
          label: label,
          quantity: quantity,
          unit: unit,
          attributes: attributes,
          imageUrl: imageUrl,
          mrp: mrp,
          sellingPrice: sellingPrice,
          costPrice: costPrice,
          allowBelowCost: allowBelowCost,
          selling: setup,
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
        // THE SENTENCE THAT SENT A MERCHANT LOOKING AT HIS OWN TYPING.
        // Every failure used to render as "check the values", including a 403
        // that had nothing to do with values: the app was calling the
        // platform's catalogue route, the server refused it, and the message
        // blamed 35000/30000/29000. The backend's own words are better than
        // anything this layer can invent, and the status decides what to add.
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(_saveFailureMessage(e, message))),
        );
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  /// The rows the merchant actually filled in, trimmed, blanks dropped.
  List<VariantAttribute> _collectAttributes() {
    final out = <VariantAttribute>[];
    final seen = <String>{};
    for (final row in _attributes) {
      final name = row.name.text.trim();
      final value = row.value.text.trim();
      if (name.isEmpty || value.isEmpty) continue;
      if (!seen.add(name.toLowerCase())) continue;
      out.add(VariantAttribute(name: name, value: value));
    }
    return out;
  }

  static String? _valueNamed(List<VariantAttribute> attributes, String name) {
    for (final a in attributes) {
      if (a.name.toLowerCase() == name) return a.value;
    }
    return null;
  }

  static double? _numberNamed(List<VariantAttribute> attributes, String name) {
    final raw = _valueNamed(attributes, name);
    if (raw == null) return null;
    // "1 kg" and "1" both mean one. Takes the leading number and ignores any
    // unit the merchant typed alongside it.
    final match = RegExp(r'[-+]?[0-9]*\.?[0-9]+').firstMatch(raw);
    return match == null ? null : double.tryParse(match.group(0)!);
  }

  /// What to actually tell the merchant when a save fails.
  ///
  /// The backend's own sentence is preferred - it knows which rule was broken.
  /// The status only decides whether to add something the merchant can act on.
  String _saveFailureMessage(Object error, String backendMessage) {
    // A ROUTE THAT DOES NOT EXIST IS NOT A MISSING LISTING. Checked before the
    // status switch, because the 404 branch below reads a 404 as "this shop no
    // longer lists that item" - which is exactly the false alarm a
    // newer-app-than-server deployment produced on a real device.
    if (meansEndpointMissing(error)) return backendMessage;
    final status = apiStatusOf(error);
    switch (status) {
      case 401:
        return 'Your session has expired. Please sign in again.';
      case 403:
        return backendMessage.isEmpty
            ? 'This shop is not allowed to change that item.'
            : backendMessage;
      case 404:
        return 'This shop no longer lists that item. Refresh and try again.';
      case 409:
        return backendMessage.isEmpty
            ? 'That was already saved, or it changed while you were editing. '
                'Refresh and check before trying again.'
            : backendMessage;
      case 400:
      case 422:
        // The one case where "check the values" is actually true - and even
        // then the server says WHICH value.
        return backendMessage;
      default:
        if (status != null && status >= 500) {
          return 'Something went wrong at our end. Your change was not saved.';
        }
        return backendMessage;
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
              // WHAT TELLS THIS VARIANT APART, in the words of whatever
              // trade this is. Optional: a shop that sells one form of
              // something has nothing to write here.
              TextFormField(
                controller: _labelController,
                decoration: const InputDecoration(
                  labelText: 'Variant name (optional)',
                  hintText: '8 GB + 128 GB, Red silk, 1 kg, Half plate',
                  helperText: 'Left blank, this is built from the details below',
                  helperMaxLines: 2,
                ),
              ),
              const SizedBox(height: 16),

              // THE DETAILS. This replaced "Pack size" and "Unit (kg, g, L...)",
              // which asked a phone merchant the grocer's question and left him
              // typing "8" under "Pack size" and "8 gb and 128 gb" under a unit
              // box meant for kilograms. A name and a value describe a phone, a
              // saree, a shoe, a strip of tablets and a bag of atta equally.
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Details',
                      style: Theme.of(context).textTheme.titleSmall),
                  TextButton.icon(
                    onPressed: _attributes.length >= _maxAttributes
                        ? null
                        : hapticize(() => setState(() =>
                            _attributes.add(_AttributeRow(name: '', value: '')))),
                    icon: const Icon(Icons.add, size: 20),
                    label: const Text('Add detail'),
                  ),
                ],
              ),
              if (_attributes.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    'Add details like RAM, Storage, Colour, Size, Material or '
                    'Pack size - whatever tells your variants apart.',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              for (int i = 0; i < _attributes.length; i++)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        flex: 4,
                        child: TextFormField(
                          controller: _attributes[i].name,
                          decoration: const InputDecoration(
                            labelText: 'Detail',
                            isDense: true,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        flex: 5,
                        child: TextFormField(
                          controller: _attributes[i].value,
                          decoration: const InputDecoration(
                            labelText: 'Value',
                            isDense: true,
                          ),
                        ),
                      ),
                      IconButton(
                        tooltip: 'Remove',
                        icon: const Icon(Icons.close, size: 20),
                        onPressed: hapticize(() => setState(() {
                              _attributes.removeAt(i).dispose();
                            })),
                      ),
                    ],
                  ),
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
              const SizedBox(height: 16),
              _sellingModeSection(),
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
                child: SizedBox(
                  height: 88,
                  width: 88,
                  // Through the app's one image pipeline. These tiles are
                  // redrawn every time the dialog rebuilds - on every field
                  // edit - and a raw Image.network refetched each original.
                  child: GpNetworkImage(
                    url: url,
                    renderWidth: 88,
                    fit: BoxFit.cover,
                    fallbackIcon: Icons.broken_image_outlined,
                    fallbackIconSize: 20,
                    placeholderColor: theme.colorScheme.surfaceContainerHighest,
                    placeholderIconColor: theme.colorScheme.outline,
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

/// The two controllers behind one editable detail row.
class _AttributeRow {
  _AttributeRow({required String name, required String value})
      : name = TextEditingController(text: name),
        value = TextEditingController(text: value);

  final TextEditingController name;
  final TextEditingController value;

  void dispose() {
    name.dispose();
    value.dispose();
  }
}
