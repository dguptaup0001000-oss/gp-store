import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/images/gp_network_image.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/marketplace_feed_models.dart';
import '../domain/marketplace_offer.dart';
import 'product_offers_provider.dart';

/// Who has this near me, and how do I get it.
///
/// THE SCREEN BEHIND A CARD THAT IS NOT A CART LINE. A Visit-to-Buy listing
/// and a service cannot open the ordinary product screen - that screen's whole
/// shape is an ADD TO CART button, and drawing one for something the backend
/// will refuse is a promise the app cannot keep.
///
/// GROUPED, NOT FILTERED. A customer who arrived on a Visit-to-Buy card and
/// could have had the thing delivered by a shop two streets further is told
/// so. Answering only within the mode they tapped answers a narrower question
/// than the one they have, which is "how do I get this?".
class ProductOffersScreen extends ConsumerWidget {
  const ProductOffersScreen({
    super.key,
    required this.card,
    this.onAdd,
  });

  final MarketplaceCard card;

  /// Adding is still the marketplace's own decision about which shop supplies
  /// it, so the home screen keeps that logic and this screen just asks.
  final Future<void> Function(MarketplaceOffer offer)? onAdd;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final offers = ref.watch(productOffersProvider(card.productId));

    return Scaffold(
      appBar: AppBar(title: Text(card.name, maxLines: 1, overflow: TextOverflow.ellipsis)),
      body: offers.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => _Message(
          icon: Icons.wifi_off_rounded,
          title: 'Could not load shops',
          body: 'Check your connection and try again.',
          action: FilledButton(
            onPressed: () => ref.invalidate(productOffersProvider(card.productId)),
            child: const Text('Try again'),
          ),
        ),
        data: (found) => found.isEmpty
            ? const _Message(
                icon: Icons.storefront_outlined,
                title: 'No shop near you has this',
                // NOT "no products available". The catalogue is full; this
                // pin simply has no shop offering this one thing, which is a
                // different sentence and a different thing to do about it.
                body: 'Try a different address, or look for something similar.',
              )
            : _Offers(card: card, offers: found, onAdd: onAdd),
      ),
    );
  }
}

class _Offers extends StatelessWidget {
  const _Offers({required this.card, required this.offers, this.onAdd});

  final MarketplaceCard card;
  final ProductOffers offers;
  final Future<void> Function(MarketplaceOffer offer)? onAdd;

  @override
  Widget build(BuildContext context) {
    final shops = offers.shopCount;
    return ListView(
      padding: const EdgeInsets.fromLTRB(14, 14, 14, 28),
      children: [
        _Head(card: card),
        const SizedBox(height: 6),
        Text(
          shops == 1 ? '1 shop near you' : '$shops shops near you',
          style: const TextStyle(
              fontSize: 12.5, color: AppColors.textSecondary),
        ),
        const SizedBox(height: 16),

        // ORDER IS DELIBERATE: what can arrive at the door first, then what
        // can be collected, then what is done at the shop. It is how much
        // effort the customer has to make, not how much anybody paid.
        _Group(
          title: 'Delivered to you',
          offers: offers.deliverable,
          onAdd: onAdd,
        ),
        _Group(title: 'Visit the shop to buy', offers: offers.visitable),
        _Group(title: 'Done at the shop', offers: offers.services),
      ],
    );
  }
}

class _Head extends StatelessWidget {
  const _Head({required this.card});

  final MarketplaceCard card;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(10),
          child: SizedBox(
            width: 78,
            height: 78,
            child: GpNetworkImage.fill(url: card.imageUrl),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(card.name,
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
              if (card.brand != null && card.brand!.isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(card.brand!,
                    style: const TextStyle(
                        fontSize: 12.5, color: AppColors.textSecondary)),
              ],
              if (card.variantUnit != null && card.variantUnit!.isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(card.variantUnit!,
                    style: const TextStyle(
                        fontSize: 12.5, color: AppColors.textSecondary)),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

/// One heading and the offers under it. Draws nothing when there are none,
/// rather than an empty heading that reads as a loading failure.
class _Group extends StatelessWidget {
  const _Group({required this.title, required this.offers, this.onAdd});

  final String title;
  final List<MarketplaceOffer> offers;
  final Future<void> Function(MarketplaceOffer offer)? onAdd;

  @override
  Widget build(BuildContext context) {
    if (offers.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title,
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
        const SizedBox(height: 8),
        for (final offer in offers) _OfferTile(offer: offer, onAdd: onAdd),
        const SizedBox(height: 18),
      ],
    );
  }
}

class _OfferTile extends StatelessWidget {
  const _OfferTile({required this.offer, this.onAdd});

  final MarketplaceOffer offer;
  final Future<void> Function(MarketplaceOffer offer)? onAdd;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.divider),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(offer.shopName ?? 'Shop',
                        style: const TextStyle(
                            fontWeight: FontWeight.w700, fontSize: 14)),
                    if (offer.distanceLabel != null) ...[
                      const SizedBox(height: 2),
                      Text(offer.distanceLabel!,
                          style: const TextStyle(
                              fontSize: 12, color: AppColors.textSecondary)),
                    ],
                  ],
                ),
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(offer.priceLabel(),
                      style: const TextStyle(
                          fontWeight: FontWeight.w800, fontSize: 15)),
                  // ONLY AN EXACT PRICE IS A PROMISE. Anything else already
                  // says so in its own label ("From", "Ask at shop"), so the
                  // struck-through MRP - which claims a saving against a
                  // committed number - is drawn only where there is one.
                  if (offer.priceMode == ListingPriceMode.exact &&
                      offer.mrp != null &&
                      offer.price != null &&
                      offer.mrp! > offer.price!)
                    Text('₹${offer.mrp!.toStringAsFixed(0)}',
                        style: const TextStyle(
                          fontSize: 11.5,
                          color: AppColors.textSecondary,
                          decoration: TextDecoration.lineThrough,
                        )),
                ],
              ),
            ],
          ),
          if (offer.availabilityLabel != null || offer.durationLabel != null) ...[
            const SizedBox(height: 6),
            Wrap(
              spacing: 6,
              runSpacing: 4,
              children: [
                if (offer.availabilityLabel != null)
                  _Chip(offer.availabilityLabel!),
                if (offer.durationLabel != null) _Chip(offer.durationLabel!),
              ],
            ),
          ],
          if (offer.whereToGo != null) ...[
            const SizedBox(height: 8),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.location_on_outlined,
                    size: 15, color: AppColors.textSecondary),
                const SizedBox(width: 4),
                Expanded(
                  child: Text(offer.whereToGo!,
                      style: const TextStyle(
                          fontSize: 12.5, color: AppColors.textSecondary)),
                ),
              ],
            ),
          ],
          const SizedBox(height: 10),
          _Actions(offer: offer, onAdd: onAdd),
        ],
      ),
    );
  }
}

/// What the customer can DO about this offer.
///
/// ADD ONLY WHERE THE SERVER SAID SO. For the other two modes the useful
/// actions are directions and a phone call - the two things somebody who has
/// to travel actually needs - and each appears only when the shop supplied
/// what it needs, rather than as a dead button that fails on tap.
class _Actions extends StatelessWidget {
  const _Actions({required this.offer, this.onAdd});

  final MarketplaceOffer offer;
  final Future<void> Function(MarketplaceOffer offer)? onAdd;

  @override
  Widget build(BuildContext context) {
    if (offer.addable) {
      return SizedBox(
        width: double.infinity,
        child: FilledButton(
          onPressed: onAdd == null ? null : hapticize(() => onAdd!(offer)),
          child: const Text('Add to cart'),
        ),
      );
    }

    final buttons = <Widget>[];
    if (offer.canBeVisited) {
      buttons.add(Expanded(
        child: OutlinedButton.icon(
          onPressed: hapticize(() => _openDirections(context, offer)),
          icon: const Icon(Icons.directions_outlined, size: 18),
          label: const Text('Directions'),
        ),
      ));
    }
    if (offer.supportPhone != null && offer.supportPhone!.trim().isNotEmpty) {
      if (buttons.isNotEmpty) buttons.add(const SizedBox(width: 8));
      buttons.add(Expanded(
        child: OutlinedButton.icon(
          onPressed: hapticize(() => _call(context, offer.supportPhone!)),
          icon: const Icon(Icons.call_outlined, size: 18),
          label: const Text('Call shop'),
        ),
      ));
    }
    if (buttons.isEmpty) {
      return Text(
        offer.commerceMode == CommerceMode.serviceAtShop
            ? 'Visit the shop for this service.'
            : 'Visit the shop to buy this.',
        style: const TextStyle(fontSize: 12.5, color: AppColors.textSecondary),
      );
    }
    return Row(children: buttons);
  }

  Future<void> _openDirections(BuildContext context, MarketplaceOffer offer) async {
    // A geo: URI is what a maps app on the device answers. The label is the
    // shop's own name so the pin is recognisable when it opens.
    final uri = Uri.parse(
        'geo:${offer.shopLatitude},${offer.shopLongitude}'
        '?q=${offer.shopLatitude},${offer.shopLongitude}'
        '(${Uri.encodeComponent(offer.shopName ?? 'Shop')})');
    await _launch(context, uri, 'No maps app to open this in.');
  }

  Future<void> _call(BuildContext context, String phone) async {
    await _launch(context, Uri(scheme: 'tel', path: phone.trim()),
        'No dialler to place this call.');
  }

  Future<void> _launch(BuildContext context, Uri uri, String ifItCannot) async {
    try {
      final opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
      if (opened || !context.mounted) return;
    } catch (_) {
      if (!context.mounted) return;
    }
    // A device with no maps app or no dialler is a real device, and a button
    // that silently does nothing reads as a broken app.
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(ifItCannot)));
  }
}

class _Chip extends StatelessWidget {
  const _Chip(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: AppColors.surfaceSoft,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(text,
          style: const TextStyle(fontSize: 11.5, color: AppColors.textSecondary)),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message({
    required this.icon,
    required this.title,
    required this.body,
    this.action,
  });

  final IconData icon;
  final String title;
  final String body;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 42, color: AppColors.textSecondary),
            const SizedBox(height: 12),
            Text(title,
                textAlign: TextAlign.center,
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
            const SizedBox(height: 6),
            Text(body,
                textAlign: TextAlign.center,
                style: const TextStyle(
                    fontSize: 13, color: AppColors.textSecondary)),
            if (action != null) ...[const SizedBox(height: 16), action!],
          ],
        ),
      ),
    );
  }
}
