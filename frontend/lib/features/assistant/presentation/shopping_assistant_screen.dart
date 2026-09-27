import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/widgets/action_feedback.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../marketplace/domain/marketplace_feed_models.dart';
import '../../marketplace/presentation/product_offers_screen.dart';

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
    if (pin == null) {
      showActionFailure(
        context,
        'Add a delivery address before searching the local marketplace.',
      );
      return;
    }
    if (_prompt.text.trim().isEmpty) {
      showActionFailure(context, 'Describe what you need.');
      return;
    }
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

  Future<void> _reviewOffer(Map<String, dynamic> raw) async {
    final card = MarketplaceCard.fromJson(raw);
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ProductOffersScreen(
          card: card,
          onAdd: (offer) async {
            final variantId = offer.productVariantId;
            if (variantId == null || !offer.addable) return;
            final added = await ref
                .read(cartControllerProvider.notifier)
                .addToCart(
                  variantId: variantId,
                  quantity: 1,
                  shopId: offer.shopId,
                );
            if (!mounted) return;
            if (added == true) {
              showAddedToCartFeedback(context, offer.productName);
            } else {
              showActionFailure(context, "Couldn't add this offer to your cart.");
            }
          },
        ),
      ),
    );
  }

  Future<void> _addReviewedOffer(Map<String, dynamic> raw) async {
    final card = MarketplaceCard.fromJson(raw);
    final variantId = card.productVariantId;
    if (!card.addable || variantId == null || card.shopId == null) {
      await _reviewOffer(raw);
      return;
    }
    try {
      final added = await ref
          .read(cartControllerProvider.notifier)
          .addToCart(
            variantId: variantId,
            quantity: 1,
            shopId: card.shopId,
          );
      if (!mounted) return;
      if (added == true) {
        showAddedToCartFeedback(context, card.name);
      } else {
        showActionFailure(context, "Couldn't add this offer to your cart.");
      }
    } catch (_) {
      if (mounted) {
        showActionFailure(context, "Couldn't add this offer to your cart.");
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final basket = (_answer?['suggestedBasket'] as List? ?? const []);
    final unavailable = (_answer?['unavailableItems'] as List? ?? const []);
    final subtotal = (_answer?['estimatedSubtotal'] as num?)?.toDouble();
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
              _BasketTile(
                data: Map<String, dynamic>.from(raw as Map),
                onReview: _reviewOffer,
                onAdd: _addReviewedOffer,
              ),
            if (subtotal != null)
              Align(
                alignment: Alignment.centerRight,
                child: Text(
                  'Estimated item subtotal: ₹${subtotal.toStringAsFixed(2)}',
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
            const Text(
              'Delivery and other shop charges are confirmed by ordinary checkout.',
            ),
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
  const _BasketTile({
    required this.data,
    required this.onReview,
    required this.onAdd,
  });
  final Map<String, dynamic> data;
  final Future<void> Function(Map<String, dynamic>) onReview;
  final Future<void> Function(Map<String, dynamic>) onAdd;

  @override
  Widget build(BuildContext context) {
    final offer = Map<String, dynamic>.from(data['offer'] as Map? ?? const {});
    final addable = offer['addable'] == true;
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.storefront_outlined),
      title: Text(offer['name'] as String? ?? data['requestedItem'] as String? ?? ''),
      subtitle: Text([
        offer['shopName'],
        offer['commerceLabel'],
        if (offer['distanceKm'] != null) '${offer['distanceKm']} km',
      ].whereType<Object>().join(' • ')),
      trailing: FilledButton.tonal(
        onPressed: () => addable ? onAdd(offer) : onReview(offer),
        child: Text(addable ? 'Review & add' : 'View options'),
      ),
      onTap: () => onReview(offer),
    );
  }
}
