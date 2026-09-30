import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/widgets/action_feedback.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../products/presentation/product_detail_screen.dart';
import '../../products/presentation/products_providers.dart';
import '../domain/marketplace_feed_models.dart';
import '../domain/marketplace_offer.dart';
import 'marketplace_feed_provider.dart';
import 'product_offers_screen.dart';

/// The one navigation/action path used by every marketplace card.
///
/// Keeping Home, category results and the infinite catalogue on this path is
/// important: otherwise one screen eventually sends a shop-product id while
/// another sends a catalogue product id, which is how customer-facing
/// "Product not found with id ..." errors return.
class MarketplaceCardActions {
  const MarketplaceCardActions._();

  static Future<void> open(
    BuildContext context,
    WidgetRef ref,
    MarketplaceCard card,
  ) async {
    if (!card.addable) {
      await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => ProductOffersScreen(
          card: card,
          onAdd: (offer) => addOffer(context, ref, offer),
        ),
      ));
      return;
    }

    try {
      final product = await ref
          .read(productsRepositoryProvider)
          .fetchProductDetail(card.productId, shopId: card.shopId);
      if (!context.mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => ProductDetailScreen(product: product)),
      );
    } catch (error) {
      if (!context.mounted) return;
      final unavailable =
          error is DioException && error.response?.statusCode == 404;
      if (unavailable) {
        ref.invalidate(marketplaceHomeModeFeedProvider(card.commerceMode));
        ref.invalidate(marketplaceHomeAllFeedProvider);
        final categoryId = card.categoryId;
        if (categoryId != null) {
          ref.invalidate(marketplaceCategoryFeedProvider(categoryId));
        }
      }
      showActionFailure(
        context,
        unavailable
            ? 'Product is no longer available. Nearby products have been refreshed.'
            : "Couldn't open this product right now. Please try again.",
      );
    }
  }

  static Future<void> addCard(
    BuildContext context,
    WidgetRef ref,
    MarketplaceCard card,
  ) async {
    final variantId = card.productVariantId;
    if (variantId == null || !card.addable) return;
    await _add(
      context,
      ref,
      variantId: variantId,
      shopId: card.shopId,
      name: card.name,
    );
  }

  static Future<void> addOffer(
    BuildContext context,
    WidgetRef ref,
    MarketplaceOffer offer,
  ) async {
    final variantId = offer.productVariantId;
    if (variantId == null || !offer.addable) return;
    await _add(
      context,
      ref,
      variantId: variantId,
      shopId: offer.shopId,
      name: offer.productName,
    );
  }

  static Future<void> _add(
    BuildContext context,
    WidgetRef ref, {
    required int variantId,
    required int? shopId,
    required String name,
  }) async {
    try {
      final added = await ref.read(cartControllerProvider.notifier).addToCart(
            variantId: variantId,
            quantity: 1,
            shopId: shopId,
          );
      if (!context.mounted) return;
      if (added == true) {
        showAddedToCartFeedback(context, name);
      } else if (added == false) {
        showActionFailure(context, "Couldn't add to cart. Please try again.");
      }
    } catch (_) {
      if (!context.mounted) return;
      showActionFailure(
        context,
        "Couldn't add the item to your cart. Please try again.",
      );
    }
  }
}
