import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';

class MerchantDemandRequestsScreen extends ConsumerStatefulWidget {
  const MerchantDemandRequestsScreen({super.key});

  @override
  ConsumerState<MerchantDemandRequestsScreen> createState() =>
      _MerchantDemandRequestsScreenState();
}

class _MerchantDemandRequestsScreenState
    extends ConsumerState<MerchantDemandRequestsScreen> {
  late Future<List<Map<String, dynamic>>> _requests;

  @override
  void initState() {
    super.initState();
    _requests = _load();
  }

  Future<List<Map<String, dynamic>>> _load() async {
    final response =
        await ref.read(apiClientProvider).dio.get('/api/shop/demand-requests');
    return (response.data as List)
        .map((row) => Map<String, dynamic>.from(row as Map))
        .toList();
  }

  Future<void> _respond(Map<String, dynamic> request, bool available) async {
    final price = TextEditingController();
    final note = TextEditingController();
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(available ? 'Respond available' : 'Respond not available'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (available)
              TextField(
                controller: price,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Price (optional)'),
              ),
            TextField(
              controller: note,
              maxLength: 500,
              decoration: const InputDecoration(labelText: 'Merchant note (optional)'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Send')),
        ],
      ),
    );
    if (accepted != true) return;
    final parsedPrice = double.tryParse(price.text);
    final requestId = request['id'];
    await ref.read(apiClientProvider).dio.put(
      '/api/shop/demand-requests/$requestId/response',
      data: {
        'status': available ? 'AVAILABLE' : 'NOT_AVAILABLE',
        if (parsedPrice != null) 'price': parsedPrice,
        if (note.text.trim().isNotEmpty) 'note': note.text.trim(),
        if (available) 'commerceMode': request['preferredMode'] ?? 'VISIT_TO_BUY',
      },
    );
    if (mounted) setState(() => _requests = _load());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Local Demand Requests')),
      body: FutureBuilder<List<Map<String, dynamic>>>(
        future: _requests,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          final requests = snapshot.data ?? const [];
          if (requests.isEmpty) {
            return const Center(child: Text('No eligible open requests nearby.'));
          }
          return ListView.builder(
            padding: const EdgeInsets.all(12),
            itemCount: requests.length,
            itemBuilder: (context, index) {
              final request = requests[index];
              return Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(request['description'] as String? ?? '',
                          style: const TextStyle(fontWeight: FontWeight.w700)),
                      Text('Quantity ${request['quantity']} • within ${request['radiusKm']} km'),
                      if (request['budget'] != null) Text('Approx. budget ₹${request['budget']}'),
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          OutlinedButton(
                            onPressed: () => _respond(request, false),
                            child: const Text('Not available'),
                          ),
                          const SizedBox(width: 8),
                          FilledButton(
                            onPressed: () => _respond(request, true),
                            child: const Text('Available'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }
}
