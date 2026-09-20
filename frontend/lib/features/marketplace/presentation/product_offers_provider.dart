import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_providers.dart';
import '../domain/marketplace_offer.dart';

/// Every nearby shop offering one product, for the screen behind a card.
///
/// FAMILY-KEYED ON THE PRODUCT, and auto-disposed, so opening six cards in a
/// row does not keep six sets of shops alive behind the one on screen.
final productOffersProvider =
    FutureProvider.autoDispose.family<ProductOffers, int>((ref, productId) async {
  final pin = ref.watch(deliveryPinProvider);
  if (pin == null) {
    // No placeable address means nobody can be told which shops serve them.
    // Empty is the honest answer; inventing a location would show a customer
    // in one town the shops of another.
    return const ProductOffers([]);
  }
  return ref.read(marketplaceRepositoryProvider).offersOf(
        productId: productId,
        latitude: pin.lat,
        longitude: pin.lng,
      );
});
