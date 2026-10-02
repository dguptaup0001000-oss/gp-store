import 'package:flutter/material.dart';

/// A suggested marketplace offer with text and actions laid out vertically.
///
/// Keeping the action below the text gives the name the full card width. A
/// trailing button in a ListTile can leave very little width on narrow phones,
/// which made long offer names wrap one character per line.
class ShoppingAssistantBasketTile extends StatelessWidget {
  const ShoppingAssistantBasketTile({
    super.key,
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
    final name = _displayText(offer['name']).isNotEmpty
        ? _displayText(offer['name'])
        : _displayText(data['requestedItem']);
    final details = <String>[
      _displayText(offer['shopName']),
      _displayText(offer['commerceLabel']),
      if (offer['distanceKm'] != null)
        '${_displayText(offer['distanceKm'])} km',
    ].where((part) => part.isNotEmpty).toList(growable: false);

    return Card(
      key: const ValueKey<String>('shopping-assistant-basket-card'),
      margin: const EdgeInsets.only(top: 10),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              name,
              key: const ValueKey<String>('shopping-assistant-offer-name'),
              softWrap: true,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
            ),
            if (details.isNotEmpty) ...[
              const SizedBox(height: 5),
              Text(
                details.join(' • '),
                softWrap: true,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const SizedBox(height: 10),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.tonal(
                onPressed: () => addable ? onAdd(offer) : onReview(offer),
                child: Text(addable ? 'Review & add' : 'View options'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  static String _displayText(Object? value) {
    if (value == null) return '';
    return value.toString().trim();
  }
}
