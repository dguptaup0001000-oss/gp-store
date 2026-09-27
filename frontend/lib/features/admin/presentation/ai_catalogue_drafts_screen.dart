import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/images/image_upload_service.dart';
import '../../../shared/widgets/status_feedback.dart';
import '../../auth/presentation/auth_providers.dart';
import 'category_picker_sheet.dart';

class AiCatalogueDraftsScreen extends ConsumerStatefulWidget {
  const AiCatalogueDraftsScreen({super.key});

  @override
  ConsumerState<AiCatalogueDraftsScreen> createState() => _AiCatalogueDraftsScreenState();
}

class _AiCatalogueDraftsScreenState extends ConsumerState<AiCatalogueDraftsScreen> {
  final _text = TextEditingController();
  late Future<List<Map<String, dynamic>>> _jobs;
  String _mediaType = 'PRODUCT_PHOTO';
  bool _uploading = false;

  @override
  void initState() {
    super.initState();
    _jobs = _load();
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<List<Map<String, dynamic>>> _load() async {
    final response = await ref.read(apiClientProvider).dio.get('/api/shop/ai-catalog/jobs');
    return (response.data as List)
        .map((row) => Map<String, dynamic>.from(row as Map))
        .toList();
  }

  Future<void> _extract() async {
    if (_text.text.trim().isEmpty) return;
    await ref.read(apiClientProvider).dio.post(
      '/api/shop/ai-catalog/jobs',
      data: {'sourceType': 'VOICE', 'manualText': _text.text.trim()},
    );
    _text.clear();
    if (mounted) setState(() => _jobs = _load());
  }

  Future<void> _extractMedia() async {
    setState(() => _uploading = true);
    try {
      final upload = ImageUploadService(
        apiClient: ref.read(apiClientProvider),
      );
      final objectKey = await upload.pickAndUpload(
        kind: CatalogImageKind.product,
      );
      if (objectKey == null) return;
      await ref.read(apiClientProvider).dio.post(
        '/api/shop/ai-catalog/jobs',
        data: {'sourceType': _mediaType, 'objectKey': objectKey},
      );
      if (mounted) {
        setState(() => _jobs = _load());
        showActionSuccess(context, 'Extraction queued. Refresh to review drafts.');
      }
    } catch (error) {
      if (mounted) {
        showActionFailure(context, 'Could not upload this catalogue source.');
      }
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _review(Map<String, dynamic> draft) async {
    final name = TextEditingController(text: draft['name'] as String? ?? '');
    final category = TextEditingController(text: '${draft['categoryId'] ?? ''}');
    final price = TextEditingController(text: '${draft['sellingPrice'] ?? ''}');
    final mrp = TextEditingController(text: '${draft['mrp'] ?? ''}');
    final stock = TextEditingController(text: '${draft['stock'] ?? ''}');
    String mode = draft['commerceMode'] as String? ?? 'ONLINE_PURCHASE';
    final save = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('Review AI-detected information'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(controller: name, decoration: const InputDecoration(labelText: 'Product name')),
                TextField(
                  controller: category,
                  readOnly: true,
                  decoration: const InputDecoration(
                    labelText: 'Category',
                    suffixIcon: Icon(Icons.search),
                  ),
                  onTap: () async {
                    final chosen = await CategoryPickerSheet.show(
                      context,
                      selectedId: int.tryParse(category.text),
                    );
                    if (chosen != null) {
                      setDialogState(() => category.text = '${chosen.id}');
                    }
                  },
                ),
                TextField(controller: price, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Selling price')),
                TextField(controller: mrp, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'MRP')),
                TextField(controller: stock, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Opening stock')),
                DropdownButtonFormField<String>(
                  initialValue: mode,
                  decoration: const InputDecoration(labelText: 'Commerce mode'),
                  items: const [
                    DropdownMenuItem(value: 'ONLINE_PURCHASE', child: Text('Buy Online')),
                    DropdownMenuItem(value: 'VISIT_TO_BUY', child: Text('Visit to Buy')),
                    DropdownMenuItem(value: 'SERVICE_AT_SHOP', child: Text('Service at Shop')),
                  ],
                  onChanged: (value) {
                    if (value != null) setDialogState(() => mode = value);
                  },
                ),
                if ((draft['uncertainFields'] as String? ?? '').isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text('Verify: ${draft['uncertainFields']}'),
                  ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Save for approval')),
          ],
        ),
      ),
    );
    if (save != true) return;
    final draftId = draft['id'];
    await ref.read(apiClientProvider).dio.put(
      '/api/shop/ai-catalog/drafts/$draftId',
      data: {
        'name': name.text.trim(),
        'brand': draft['brand'],
        'description': draft['description'],
        'categoryId': int.tryParse(category.text),
        'variantLabel': draft['variantLabel'],
        'quantity': draft['quantity'],
        'unit': draft['unit'],
        'sellingPrice': double.tryParse(price.text),
        'mrp': double.tryParse(mrp.text),
        'stock': int.tryParse(stock.text),
        'barcode': draft['barcode'],
        'imageUrl': draft['imageUrl'],
        'commerceMode': mode,
      },
    );
    if (mounted) setState(() => _jobs = _load());
  }

  Future<void> _approve(Map<String, dynamic> draft) async {
    final draftId = draft['id'];
    await ref.read(apiClientProvider).dio.post(
      '/api/shop/ai-catalog/drafts/$draftId/approve',
    );
    if (mounted) setState(() => _jobs = _load());
  }

  Future<void> _reject(Map<String, dynamic> draft) async {
    final draftId = draft['id'];
    await ref.read(apiClientProvider).dio.post(
      '/api/shop/ai-catalog/drafts/$draftId/reject',
    );
    if (mounted) setState(() => _jobs = _load());
  }

  Future<void> _approveBatch(List<Map<String, dynamic>> drafts) async {
    final ids = drafts
        .where((draft) =>
            draft['status'] == 'REVIEW' &&
            draft['categoryId'] != null &&
            draft['sellingPrice'] != null)
        .map((draft) => draft['id'])
        .take(50)
        .toList();
    if (ids.isEmpty) return;
    await ref.read(apiClientProvider).dio.post(
      '/api/shop/ai-catalog/drafts/batch-approve',
      data: {'draftIds': ids},
    );
    if (mounted) setState(() => _jobs = _load());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('AI Add & Bulk Digitize')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text(
            'AI creates drafts only. Review every detected value before publishing.',
            style: TextStyle(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _text,
            decoration: const InputDecoration(
              labelText: 'Type or dictate a product',
              hintText: '50 packet Surf Excel, 10 each',
            ),
          ),
          const SizedBox(height: 8),
          FilledButton.icon(
            onPressed: _extract,
            icon: const Icon(Icons.auto_awesome_outlined),
            label: const Text('Create review draft'),
          ),
          const SizedBox(height: 16),
          const Text(
            'Or digitize a controlled photo source',
            style: TextStyle(fontWeight: FontWeight.w700),
          ),
          DropdownButtonFormField<String>(
            initialValue: _mediaType,
            items: const [
              DropdownMenuItem(value: 'PRODUCT_PHOTO', child: Text('Product photograph')),
              DropdownMenuItem(value: 'LABEL_PHOTO', child: Text('Package or label photograph')),
              DropdownMenuItem(value: 'SHELF_PHOTO', child: Text('Shelf photograph')),
              DropdownMenuItem(value: 'INVOICE', child: Text('Invoice or bill photograph')),
              DropdownMenuItem(value: 'PRICE_LIST', child: Text('Price-list photograph')),
              DropdownMenuItem(value: 'CATALOGUE_PAGE', child: Text('Catalogue page photograph')),
            ],
            onChanged: _uploading
                ? null
                : (value) {
                    if (value != null) setState(() => _mediaType = value);
                  },
          ),
          OutlinedButton.icon(
            onPressed: _uploading ? null : _extractMedia,
            icon: _uploading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.document_scanner_outlined),
            label: const Text('Upload and queue extraction'),
          ),
          const SizedBox(height: 20),
          FutureBuilder<List<Map<String, dynamic>>>(
            future: _jobs,
            builder: (context, snapshot) {
              if (snapshot.connectionState != ConnectionState.done) {
                return const Center(child: CircularProgressIndicator());
              }
              final jobs = snapshot.data ?? const [];
              final drafts = jobs
                  .expand((job) => (job['drafts'] as List? ?? const []))
                  .map((row) => Map<String, dynamic>.from(row as Map))
                  .toList();
              if (jobs.isEmpty) return const Text('No catalogue drafts yet.');
              return Column(
                children: [
                  for (final job in jobs.where((job) =>
                      job['status'] == 'QUEUED' ||
                      job['status'] == 'PROCESSING' ||
                      job['status'] == 'FAILED'))
                    ListTile(
                      leading: job['status'] == 'FAILED'
                          ? const Icon(Icons.error_outline)
                          : const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                      title: Text('${job['sourceType']} • ${job['status']}'),
                      subtitle: job['errorCode'] == null
                          ? null
                          : Text('Extraction failed: ${job['errorCode']}'),
                    ),
                  if (drafts.where((draft) =>
                      draft['status'] == 'REVIEW' &&
                      draft['categoryId'] != null &&
                      draft['sellingPrice'] != null).length > 1)
                    Align(
                      alignment: Alignment.centerRight,
                      child: FilledButton.tonalIcon(
                        onPressed: () => _approveBatch(drafts),
                        icon: const Icon(Icons.done_all),
                        label: const Text('Approve reviewed batch'),
                      ),
                    ),
                  for (final draft in drafts)
                    Card(
                      child: ListTile(
                        title: Text(draft['name'] as String? ?? 'Unnamed draft'),
                        subtitle: Text([
                          '${draft['status']}',
                          'confidence ${draft['confidence'] ?? 'unknown'}',
                          if (draft['matchedProductId'] != null)
                            'canonical match found — approval reuses it',
                        ].join(' • ')),
                        onTap: draft['status'] == 'REVIEW' ? () => _review(draft) : null,
                        trailing: draft['status'] == 'REVIEW'
                            ? Wrap(
                                children: [
                                  TextButton(
                                    onPressed: () => _reject(draft),
                                    child: const Text('Reject'),
                                  ),
                                  FilledButton(
                                    onPressed: draft['categoryId'] != null && draft['sellingPrice'] != null
                                        ? () => _approve(draft)
                                        : null,
                                    child: const Text('Approve'),
                                  ),
                                ],
                              )
                            : null,
                      ),
                    ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }
}
