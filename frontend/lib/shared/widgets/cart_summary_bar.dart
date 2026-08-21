import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/images/product_image_url.dart';

import '../../core/theme/app_theme.dart';
import '../../features/cart/domain/cart_models.dart';
import '../../features/cart/presentation/cart_providers.dart';
import '../../features/cart/presentation/cart_screen.dart';

/// The floating "N items · View cart" bar - drop this in as any screen's
/// Scaffold.bottomNavigationBar. Self-contained (reads the cart itself via
/// cartControllerProvider) so it reacts live the instant an item is added
/// anywhere in the app, without the caller needing to wire up item
/// count/total/navigation by hand. Was previously home_screen.dart's private
/// _HomeCartBar - extracted so every screen where a customer actually adds
/// items (search, category/brand browsing, product detail) can show the
/// same instant "you have items waiting" shortcut, not just the home screen.
///
/// Stadium pill with stacked item thumbnails in the brand's forest green -
/// the same green as every ADD button that filled it, so the bar reads as
/// the destination those taps were heading towards.
class CartSummaryBar extends ConsumerWidget {
  const CartSummaryBar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cart = ref.watch(cartControllerProvider).valueOrNull;
    final items = cart?.items ?? const <CartItemModel>[];
    final itemCount = cart?.totalItems ?? 0;

    if (itemCount <= 0) return const SizedBox.shrink();

    final thumbnails = items.take(3).toList();

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: Material(
          color: AppColors.primary,
          shape: const StadiumBorder(),
          elevation: 4,
          child: InkWell(
            customBorder: const StadiumBorder(),
            onTap: () {
              HapticFeedback.selectionClick();
              Navigator.of(context).push(MaterialPageRoute(builder: (_) => const CartScreen()));
            },
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
              child: Row(
                children: [
                  _ThumbnailStack(items: thumbnails),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Text(
                          'View cart',
                          style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 15),
                        ),
                        Text(
                          '$itemCount item${itemCount == 1 ? '' : 's'}',
                          style: const TextStyle(color: Colors.white70, fontSize: 12, fontWeight: FontWeight.w500),
                        ),
                      ],
                    ),
                  ),
                  Container(
                    padding: const EdgeInsets.all(6),
                    decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                    child: const Icon(Icons.arrow_forward, color: AppColors.primary, size: 18),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Up to 3 overlapping circular item thumbnails, most-recently-added last
/// (drawn on top) - a quick "here's what's in there" preview instead of
/// just a bare count.
class _ThumbnailStack extends StatelessWidget {
  const _ThumbnailStack({required this.items});

  final List<CartItemModel> items;

  static const _size = 34.0;
  static const _overlap = 20.0;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) {
      return const SizedBox(
        width: _size,
        height: _size,
        child: Icon(Icons.shopping_bag_outlined, color: Colors.white, size: 20),
      );
    }

    return SizedBox(
      width: _size + (items.length - 1) * _overlap,
      height: _size,
      child: Stack(
        children: [
          for (var i = 0; i < items.length; i++)
            Positioned(
              left: i * _overlap,
              child: Container(
                width: _size,
                height: _size,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: Colors.white,
                  border: Border.all(color: AppColors.primary, width: 2),
                ),
                clipBehavior: Clip.antiAlias,
                child: items[i].imageUrl != null
                    ? CachedNetworkImage(
                        imageUrl: ProductImageUrl.tile(items[i].imageUrl),
                        fit: BoxFit.contain,
                        // 34x34 avatar - avoid decoding the full original.
                        memCacheWidth: 100,
                        errorWidget: (context, url, error) =>
                            const Icon(Icons.shopping_bag_outlined, color: AppColors.primary, size: 16),
                      )
                    : const Icon(Icons.shopping_bag_outlined, color: AppColors.primary, size: 16),
              ),
            ),
        ],
      ),
    );
  }
}
