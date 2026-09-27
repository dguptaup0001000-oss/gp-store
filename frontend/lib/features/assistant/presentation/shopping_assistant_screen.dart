import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../../auth/presentation/auth_providers.dart';

class ShoppingAssistantScreen extends ConsumerStatefulWidget {
  const ShoppingAssistantScreen({super.key});

  @override
  ConsumerState<ShoppingAssistantScreen> createState() => _ShoppingAssistantScreenState();
}

class _ShoppingAssistantScreenState extends ConsumerState<ShoppingAssistantScreen> {
  final _prompt = TextEditingController();
  Map<String, dynamic>? _answer;
  String? _error;
  bool _loading = false;

  @override
  void dispose() {
    _prompt.dispose();
    super.dispose();
  }

  Future<void> _ask() async {
    final pin = ref.read(deliveryPinProvider);
    if (pin == null || _prompt.text.trim().isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final response = await ref.read(apiClientProvider).dio.post(
        '/api/marketplace/assistant',
        data: {
          'prompt': _prompt.text.trim(),
          'latitude': pin.lat,
          'longitude': pin.lng,
        },
      );
      if (mounted) setState(() => _answer = Map<String, dynamic>.from(response.data as Map));
    } catch (error) {
      if (mounted) setState(() => _error = 'Could not interpret that request. Ordinary search is still available.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final basket = (_answer?['suggestedBasket'] as List? ?? const []);
    final unavailable = (_answer?['unavailableItems'] as List? ?? const []);
    return Scaffold(
      appBar: AppBar(title: const Text('Shopping Assistant')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text(
            'Describe what you need in English, Hindi, or Hinglish. Suggestions use live GP-STORE listings and are never added automatically.',
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _prompt,
            maxLines: 3,
            decoration: const InputDecoration(
              hintText: 'Groceries for biryani for 10 people under ₹1500',
            ),
          ),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: _loading ? null : _ask,
            child: _loading
                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Find real local options'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          if (basket.isNotEmpty) ...[
            const SizedBox(height: 20),
            const Text('Suggested basket — review before adding', style: TextStyle(fontWeight: FontWeight.w700)),
            for (final raw in basket)
              _BasketTile(data: Map<String, dynamic>.from(raw as Map)),
          ],
          if (unavailable.isNotEmpty) ...[
            const SizedBox(height: 16),
            const Text('Not found nearby', style: TextStyle(fontWeight: FontWeight.w700)),
            for (final item in unavailable) ListTile(leading: const Icon(Icons.search_off), title: Text('$item')),
          ],
        ],
      ),
    );
  }
}

class _BasketTile extends StatelessWidget {
  const _BasketTile({required this.data});
  final Map<String, dynamic> data;

  @override
  Widget build(BuildContext context) {
    final offer = Map<String, dynamic>.from(data['offer'] as Map? ?? const {});
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.storefront_outlined),
      title: Text(offer['name'] as String? ?? data['requestedItem'] as String? ?? ''),
      subtitle: Text([
        offer['shopName'],
        offer['commerceLabel'],
        if (offer['distanceKm'] != null) '${offer['distanceKm']} km',
      ].whereType<Object>().join(' • ')),
      trailing: offer['sellingPrice'] == null ? null : Text('₹${offer['sellingPrice']}'),
    );
  }
}
