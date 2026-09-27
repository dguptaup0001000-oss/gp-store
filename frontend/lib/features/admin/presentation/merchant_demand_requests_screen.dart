import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/images/gp_network_image.dart';
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
    final quantity = TextEditingController(
      text: available ? '${request['quantity'] ?? 1}' : '',
    );
    final readyMinutes = TextEditingController();
    final note = TextEditingController();
    String selectedMode =
        request['preferredMode'] as String? ?? 'VISIT_TO_BUY';
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(available ? 'Respond available' : 'Respond not available'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (available) ...[
                  TextField(
                    controller: price,
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    decoration:
                        const InputDecoration(labelText: 'Price (optional)'),
                  ),
                  TextField(
                    controller: quantity,
                    keyboardType: TextInputType.number,
                    decoration:
                        const InputDecoration(labelText: 'Quantity available'),
                  ),
                  TextField(
                    controller: readyMinutes,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Ready in minutes (optional)',
                    ),
                  ),
                  DropdownButtonFormField<String>(
                    initialValue: selectedMode,
                    decoration:
                        const InputDecoration(labelText: 'How customer buys'),
                    items: const [
                      DropdownMenuItem(
                        value: 'ONLINE_PURCHASE',
                        child: Text('Buy Online'),
                      ),
                      DropdownMenuItem(
                        value: 'VISIT_TO_BUY',
                        child: Text('Visit Shop'),
                      ),
                      DropdownMenuItem(
                        value: 'SERVICE_AT_SHOP',
                        child: Text('Service at Shop'),
                      ),
                    ],
                    onChanged: (value) {
                      if (value != null) {
                        setDialogState(() => selectedMode = value);
                      }
                    },
                  ),
                ],
                TextField(
                  controller: note,
                  maxLength: 500,
                  decoration: const InputDecoration(
                    labelText: 'Merchant note (optional)',
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Send'),
            ),
          ],
        ),
      ),
    );
    if (accepted != true) return;
    final parsedPrice = double.tryParse(price.text);
    final parsedQuantity = int.tryParse(quantity.text);
    final parsedReadyMinutes = int.tryParse(readyMinutes.text);
    final requestId = request['id'];
    await ref.read(apiClientProvider).dio.put(
      '/api/shop/demand-requests/$requestId/response',
      data: {
        'status': available ? 'AVAILABLE' : 'NOT_AVAILABLE',
        if (parsedPrice != null) 'price': parsedPrice,
        if (available && parsedQuantity != null) 'quantity': parsedQuantity,
        if (available && parsedReadyMinutes != null)
          'readyMinutes': parsedReadyMinutes,
        if (note.text.trim().isNotEmpty) 'note': note.text.trim(),
        if (available) 'commerceMode': selectedMode,
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
          return RefreshIndicator(
            onRefresh: () async {
              setState(() => _requests = _load());
              await _requests;
            },
            child: ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(12),
              itemCount: requests.length,
              itemBuilder: (context, index) {
                final request = requests[index];
                final myResponse = request['myResponse'] as Map?;
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
                      if (request['preferredMode'] != null)
                        Text('Preferred: ${request['preferredMode'].toString().replaceAll('_', ' ')}'),
                      if (request['requiredBy'] != null)
                        Text('Required by: ${request['requiredBy']}'),
                      if (request['photoUrl'] != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: SizedBox(
                            height: 120,
                            width: 120,
                            child: GpNetworkImage(
                              url: request['photoUrl'] as String?,
                              renderWidth: 120,
                              fit: BoxFit.cover,
                            ),
                          ),
                        ),
                      if (myResponse != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: Text(
                            'Your response: ${myResponse['status']}'
                            '${myResponse['price'] == null ? '' : ' • ₹${myResponse['price']}'}',
                            style: const TextStyle(fontWeight: FontWeight.w600),
                          ),
                        ),
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          OutlinedButton(
                            onPressed: () => _respond(request, false),
                            child: Text(myResponse == null
                                ? 'Not available'
                                : 'Update: not available'),
                          ),
                          const SizedBox(width: 8),
                          FilledButton(
                            onPressed: () => _respond(request, true),
                            child: Text(myResponse == null
                                ? 'Available'
                                : 'Update response'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                );
              },
            ),
          );
        },
      ),
    );
  }
}
