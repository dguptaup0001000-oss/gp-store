import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/images/gp_network_image.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../products/domain/product_models.dart';
import '../../products/presentation/product_detail_screen.dart';
import '../../products/presentation/products_providers.dart';

/// What is actually on this shop's shelf, read without moving in.
///
/// THE SHOP PAGE USED TO BE A REFERENCE CARD - rating, hours, delivery radius,
/// returns policy - and a customer deciding between two chemists cannot decide
/// on any of that. What they want is what the shop HAS, at that shop's prices,
/// before they commit to it.
///
/// READ WITH THAT SHOP'S ID ON THE REQUEST, not by switching to it. Switching
/// throws away the categories, the feed and the basket pricing belonging to
/// the shop the customer is actually in, and doing that to somebody who only
/// wanted to look is the app making their decision for them.
///
/// THERE IS NO ADD BUTTON HERE, AND THAT IS THE POINT. The basket is scoped to
/// the shop the app is acting for; an ADD on this grid would put Sharma
/// Medical's paracetamol into Gupta Kirana's basket at Gupta Kirana's price.
/// Tapping a product switches to this shop first - which is unambiguous,
/// because the customer tapped it inside this shop's own page - and then opens
/// it. One deliberate act, in the place where the intent is clearest.
class ShopShelfPreview extends ConsumerWidget {
  const ShopShelfPreview({
    super.key,
    required this.shopId,
    required this.shopName,
    this.howMany = 6,
  });

  final int shopId;
  final String shopName;

  /// A preview, not the shelf. "Shop here" opens the whole catalogue, which
  /// is paginated and endless; this is the handful that answers "is it worth
  /// going in".
  final int howMany;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final shelfAsync = ref.watch(shopShelfPreviewProvider(shopId));

    return shelfAsync.when(
      // A shelf that is slow must not hold up the rating, the hours and the
      // returns policy above it - those are useful on their own.
      loading: () => const SizedBox(
        height: 150,
        child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
      ),
      error: (_, __) => const SizedBox.shrink(),
      data: (products) {
        if (products.isEmpty) {
          // AN EMPTY SHELF IS A REAL ANSWER about a real shop - a merchant who
          // has been approved and has not listed anything yet - and it is
          // worth more to a customer than a missing section.
          return const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Text(
              'This shop has not put anything on its shelf yet.',
              style: TextStyle(fontSize: 13, color: AppColors.textSecondary),
            ),
          );
        }
        final preview = products.take(howMany).toList();
        return SizedBox(
          height: 168,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: EdgeInsets.zero,
            itemCount: preview.length,
            separatorBuilder: (_, __) => const SizedBox(width: 10),
            itemBuilder: (context, index) => _ShelfTile(
              product: preview[index],
              shopId: shopId,
            ),
          ),
        );
      },
    );
  }
}

/// One item on another shop's shelf: its photo, its name, and that shop's price.
class _ShelfTile extends ConsumerWidget {
  const _ShelfTile({required this.product, required this.shopId});

  final Product product;
  final int shopId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final variant = product.variants.isEmpty ? null : product.variants.first;
    final price = variant?.sellingPrice;

    return SizedBox(
      width: 118,
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: hapticize(() {
          // SWITCHES FIRST, THEN OPENS. The product screen prices and stocks
          // against the shop the app is acting for, so opening it without
          // switching would show this shop's item at the other shop's price -
          // and the ADD on it would go to the other shop's basket.
          ref.read(shopSwitchProvider).select(shopId);
          Navigator.of(context).pushReplacement(
            MaterialPageRoute(builder: (_) => ProductDetailScreen(product: product)),
          );
        }),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              height: 100,
              width: double.infinity,
              decoration: BoxDecoration(
                color: AppColors.surfaceSoft,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.divider),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(10),
                // CONTAIN, like the product card: an atta bag cropped to fill
                // a square stops being recognisable, which is the one job the
                // picture has. The image lives on the variant, not the
                // product - the same place ProductCard reads it from.
                child: GpNetworkImage(
                  url: variant?.imageUrl,
                  renderWidth: 118,
                  fallbackIcon: Icons.shopping_basket_outlined,
                ),
              ),
            ),
            const SizedBox(height: 5),
            Text(
              product.name,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                  fontSize: 11.5, height: 1.2, fontWeight: FontWeight.w600),
            ),
            if (price != null)
              Text(
                '₹${price.toStringAsFixed(0)}',
                style: const TextStyle(
                    fontSize: 12.5, fontWeight: FontWeight.w800, height: 1.3),
              ),
          ],
        ),
      ),
    );
  }
}
