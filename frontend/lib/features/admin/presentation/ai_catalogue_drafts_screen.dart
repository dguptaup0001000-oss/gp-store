import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';

class AiCatalogueDraftsScreen extends ConsumerStatefulWidget {
  const AiCatalogueDraftsScreen({super.key});

  @override
  ConsumerState<AiCatalogueDraftsScreen> createState() => _AiCatalogueDraftsScreenState();
}

class _AiCatalogueDraftsScreenState extends ConsumerState<AiCatalogueDraftsScreen> {
  final _text = TextEditingController();
  late Future<List<Map<String, dynamic>>> _jobs;

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

  Future<void> _review(Map<String, dynamic> draft) async {
    final name = TextEditingController(text: draft['name'] as String? ?? '');
    final category = TextEditingController(text: '${draft['categoryId'] ?? ''}');
    final price = TextEditingController(text: '${draft['sellingPrice'] ?? ''}');
    final mrp = TextEditingController(text: '${draft['mrp'] ?? ''}');
    final stock = TextEditingController(text: '${draft['stock'] ?? ''}');
    final save = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Review AI-detected information'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(controller: name, decoration: const InputDecoration(labelText: 'Product name')),
              TextField(controller: category, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Category ID')),
              TextField(controller: price, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Selling price')),
              TextField(controller: mrp, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'MRP')),
              TextField(controller: stock, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Opening stock')),
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
    );
    if (save != true) return;
    await ref.read(apiClientProvider).dio.put(
      '/api/shop/ai-catalog/drafts/${draft['id']}',
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
        'commerceMode': draft['commerceMode'] ?? 'ONLINE_PURCHASE',
      },
    );
    if (mounted) setState(() => _jobs = _load());
  }

  Future<void> _approve(Map<String, dynamic> draft) async {
    await ref.read(apiClientProvider).dio.post(
      '/api/shop/ai-catalog/drafts/${draft['id']}/approve',
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
          const SizedBox(height: 20),
          FutureBuilder<List<Map<String, dynamic>>>(
            future: _jobs,
            builder: (context, snapshot) {
              if (snapshot.connectionState != ConnectionState.done) {
                return const Center(child: CircularProgressIndicator());
              }
              final drafts = (snapshot.data ?? const [])
                  .expand((job) => (job['drafts'] as List? ?? const []))
                  .map((row) => Map<String, dynamic>.from(row as Map))
                  .toList();
              if (drafts.isEmpty) return const Text('No catalogue drafts yet.');
              return Column(
                children: [
                  for (final draft in drafts)
                    Card(
                      child: ListTile(
                        title: Text(draft['name'] as String? ?? 'Unnamed draft'),
                        subtitle: Text('${draft['status']} • confidence ${draft['confidence'] ?? 'unknown'}'),
                        onTap: draft['status'] == 'REVIEW' ? () => _review(draft) : null,
                        trailing: draft['status'] == 'REVIEW'
                            ? FilledButton(
                                onPressed: draft['categoryId'] != null && draft['sellingPrice'] != null
                                    ? () => _approve(draft)
                                    : null,
                                child: const Text('Approve'),
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
