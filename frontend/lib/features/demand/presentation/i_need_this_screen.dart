import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/images/gp_network_image.dart';
import '../../../core/images/image_upload_service.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../shared/widgets/action_feedback.dart';
import '../../products/presentation/products_providers.dart';
import '../../marketplace/presentation/shop_profile_screen.dart';
import '../data/demand_repository.dart';
import 'demand_providers.dart';

class INeedThisScreen extends ConsumerStatefulWidget {
  const INeedThisScreen({super.key, this.initialDescription = ''});
  final String initialDescription;

  @override
  ConsumerState<INeedThisScreen> createState() => _INeedThisScreenState();
}

class _INeedThisScreenState extends ConsumerState<INeedThisScreen> {
  late final TextEditingController _description;
  final _quantity = TextEditingController(text: '1');
  final _budget = TextEditingController();
  double _radius = 8;
  String? _mode;
  int? _categoryId;
  DateTime? _requiredBy;
  String? _photoUrl;
  bool _uploadingPhoto = false;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _description = TextEditingController(text: widget.initialDescription);
  }

  @override
  void dispose() {
    _description.dispose();
    _quantity.dispose();
    _budget.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final pin = ref.read(deliveryPinProvider);
    if (pin == null) {
      showActionFailure(context, 'Add a delivery address before sending a local request.');
      return;
    }
    if (_description.text.trim().isEmpty) {
      showActionFailure(context, 'Describe the item or service you need.');
      return;
    }
    setState(() => _saving = true);
    try {
      final request = await ref.read(demandRepositoryProvider).create(
            description: _description.text.trim(),
            latitude: pin.lat,
            longitude: pin.lng,
            quantity: int.tryParse(_quantity.text) ?? 1,
            budget: double.tryParse(_budget.text),
            categoryId: _categoryId,
            requiredBy: _requiredBy,
            photoUrl: _photoUrl,
            radiusKm: _radius,
            preferredMode: _mode,
          );
      ref.invalidate(myDemandRequestsProvider);
      if (!mounted) return;
      Navigator.of(context).pop(request);
    } catch (error) {
      if (mounted) showActionFailure(context, extractErrorMessage(error));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _pickPhoto() async {
    final picked = await ImagePicker().pickImage(
      source: ImageSource.gallery,
      imageQuality: 80,
      maxWidth: 1600,
      maxHeight: 1600,
    );
    if (picked == null || !mounted) return;
    setState(() => _uploadingPhoto = true);
    try {
      final upload = ImageUploadService(
        apiClient: ref.read(demandRepositoryProvider).apiClient,
      );
      final stagingKey = await upload.uploadProfilePhoto(
        bytes: await picked.readAsBytes(),
      );
      final response = await ref.read(demandRepositoryProvider).apiClient.dio.post(
        '/api/demand-requests/photo/confirm',
        data: {'objectKey': stagingKey},
      );
      final url = (response.data as Map)['photoUrl'] as String?;
      if (url == null || url.isEmpty) throw StateError('Missing photo URL');
      if (mounted) setState(() => _photoUrl = url);
    } catch (error) {
      if (mounted) {
        showActionFailure(context, extractErrorMessage(error));
      }
    } finally {
      if (mounted) setState(() => _uploadingPhoto = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final categories = ref.watch(categoriesProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('I Need This')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text(
            'Nearby eligible shops can respond inside GP-STORE. Your exact address and contact details are never shared.',
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _description,
            maxLength: 500,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: 'What do you need?',
              hintText: 'Example: tractor brake pad for model…',
            ),
          ),
          Row(
            children: [
              if (_photoUrl != null)
                SizedBox(
                  width: 72,
                  height: 72,
                  child: GpNetworkImage(
                    url: _photoUrl,
                    renderWidth: 72,
                    fit: BoxFit.cover,
                  ),
                ),
              TextButton.icon(
                onPressed: _uploadingPhoto ? null : _pickPhoto,
                icon: _uploadingPhoto
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.add_a_photo_outlined),
                label: Text(_photoUrl == null ? 'Add photo' : 'Replace photo'),
              ),
              if (_photoUrl != null)
                IconButton(
                  tooltip: 'Remove photo',
                  onPressed: () => setState(() => _photoUrl = null),
                  icon: const Icon(Icons.close),
                ),
            ],
          ),
          TextField(
            controller: _quantity,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: 'Quantity'),
          ),
          TextField(
            controller: _budget,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Approximate budget (optional)'),
          ),
          const SizedBox(height: 12),
          categories.when(
            loading: () => const LinearProgressIndicator(),
            error: (_, __) => const Text(
              'Categories are unavailable. You can still send this request.',
            ),
            data: (items) => DropdownButtonFormField<int?>(
              initialValue: _categoryId,
              decoration: const InputDecoration(labelText: 'Category (optional)'),
              items: [
                const DropdownMenuItem(value: null, child: Text('Not sure')),
                for (final category in items)
                  DropdownMenuItem(
                    value: category.id,
                    child: Text(category.name, overflow: TextOverflow.ellipsis),
                  ),
              ],
              onChanged: (value) => setState(() => _categoryId = value),
            ),
          ),
          const SizedBox(height: 12),
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Required by (optional)'),
            subtitle: Text(
              _requiredBy == null
                  ? 'Request remains open for up to 7 days'
                  : MaterialLocalizations.of(context).formatMediumDate(_requiredBy!),
            ),
            trailing: _requiredBy == null
                ? const Icon(Icons.calendar_today_outlined)
                : IconButton(
                    tooltip: 'Clear required date',
                    onPressed: () => setState(() => _requiredBy = null),
                    icon: const Icon(Icons.close),
                  ),
            onTap: () async {
              final now = DateTime.now();
              final selected = await showDatePicker(
                context: context,
                initialDate: _requiredBy ?? now.add(const Duration(days: 1)),
                firstDate: now,
                lastDate: now.add(const Duration(days: 30)),
              );
              if (selected != null && mounted) {
                setState(() {
                  _requiredBy = DateTime(
                    selected.year,
                    selected.month,
                    selected.day,
                    23,
                    59,
                  );
                });
              }
            },
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<String?>(
            initialValue: _mode,
            decoration: const InputDecoration(labelText: 'Preferred way to buy'),
            items: const [
              DropdownMenuItem(value: null, child: Text('Any supported mode')),
              DropdownMenuItem(value: 'ONLINE_PURCHASE', child: Text('Buy Online')),
              DropdownMenuItem(value: 'VISIT_TO_BUY', child: Text('Visit to Buy')),
              DropdownMenuItem(value: 'SERVICE_AT_SHOP', child: Text('Service at Shop')),
            ],
            onChanged: (value) => setState(() => _mode = value),
          ),
          const SizedBox(height: 12),
          Text('Search radius: ${_radius.round()} km'),
          Slider(
            value: _radius,
            min: 2,
            max: 50,
            divisions: 24,
            label: '${_radius.round()} km',
            onChanged: (value) => setState(() => _radius = value),
          ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _saving ? null : _submit,
            child: _saving
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text('Send to eligible local shops'),
          ),
        ],
      ),
    );
  }
}

class DemandRequestCard extends StatelessWidget {
  const DemandRequestCard({
    super.key,
    required this.request,
    this.onClose,
    this.onCancel,
    this.onReportResponse,
  });
  final DemandRequest request;
  final VoidCallback? onClose;
  final VoidCallback? onCancel;
  final ValueChanged<DemandResponse>? onReportResponse;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(request.description, style: const TextStyle(fontWeight: FontWeight.w700)),
            if (request.photoUrl != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: SizedBox(
                  width: 120,
                  height: 120,
                  child: GpNetworkImage(
                    url: request.photoUrl,
                    renderWidth: 120,
                    fit: BoxFit.cover,
                  ),
                ),
              ),
            Text('${request.status} • ${request.responses.length} responses'),
            for (final response in request.responses)
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(response.shopName),
                subtitle: Text([
                  response.status == 'AVAILABLE' ? 'Available' : 'Not available',
                  if (response.quantity != null) 'Qty ${response.quantity}',
                  if (response.readyMinutes != null)
                    'Ready in ${response.readyMinutes} min',
                  if (response.commerceMode != null)
                    response.commerceMode!.replaceAll('_', ' '),
                  if (response.note != null) response.note!,
                ].join(' • ')),
                trailing: Wrap(
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    if (response.price != null) Text('₹${response.price}'),
                    if (onReportResponse != null)
                      IconButton(
                        tooltip: 'Report or block this response',
                        onPressed: () => onReportResponse!(response),
                        icon: const Icon(Icons.flag_outlined),
                      ),
                  ],
                ),
                onTap: response.status != 'AVAILABLE'
                    ? null
                    : () => Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (_) =>
                                ShopProfileScreen(shopId: response.shopId),
                          ),
                        ),
              ),
            if (onClose != null || onCancel != null)
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  if (onCancel != null)
                    TextButton(
                      onPressed: onCancel,
                      child: const Text('Cancel request'),
                    ),
                  if (onClose != null)
                    FilledButton.tonal(
                      onPressed: onClose,
                      child: const Text('Close request'),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}
