import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_models.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../marketplace/presentation/category_shops_screen.dart';
import '../../marketplace/presentation/shop_profile_screen.dart';
import '../domain/product_models.dart';
import 'category_products_screen.dart';
import 'products_providers.dart';

/// Shops and categories that match what the customer typed, above the products.
///
/// SEARCH ON A MARKETPLACE HAS THREE ANSWERS, not one. "Sharma Medical" is a
/// shop, "medicine" is a category, and "crocin" is a product - and a search
/// box that only ever answers the third sends somebody looking for a chemist
/// through a list of paracetamol strips to find one.
///
/// MATCHED LOCALLY, AGAINST WHAT THE APP ALREADY HAS, and that is the right
/// answer rather than a shortcut. The shops it searches are the ones the
/// server said serve this customer's address, in the server's own order; the
/// categories are the catalogue already loaded for the home screen. Somebody
/// typing "Sharma Medical" wants the Sharma Medical that delivers to them, not
/// one in another city - so a name search across every shop on the platform
/// would be a worse answer as well as a new endpoint.
///
/// NOTHING IS INVENTED WHEN THERE IS NOTHING TO SHOW. No matches draws no
/// section, so the products keep the whole screen.
class SearchMatches extends ConsumerWidget {
  const SearchMatches({super.key, required this.query});

  final String query;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final term = query.trim().toLowerCase();
    if (term.length < 2) return const SizedBox.shrink();

    final marketplace = ref.watch(isMarketplaceProvider);
    final shops = marketplace ? _matchingShops(ref, term) : const <Storefront>[];
    final categories = _matchingCategories(ref, term);

    if (shops.isEmpty && categories.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (shops.isNotEmpty) ...[
          const _Heading('Shops'),
          for (final shop in shops)
            _Row(
              icon: Icons.storefront_outlined,
              label: shop.displayName ?? 'Shop ${shop.shopId}',
              detail: shop.distanceKm == null
                  ? null
                  : '${shop.distanceKm!.toStringAsFixed(1)} km',
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                    builder: (_) => ShopProfileScreen(shopId: shop.shopId)),
              ),
            ),
        ],
        if (categories.isNotEmpty) ...[
          const _Heading('Categories'),
          for (final category in categories)
            _Row(
              icon: Icons.category_outlined,
              label: category.name,
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => marketplace
                      ? CategoryShopsScreen(
                          categoryId: category.id, categoryName: category.name)
                      : CategoryProductsScreen(category: category),
                ),
              ),
            ),
        ],
        const Divider(height: 16, color: AppColors.divider),
      ],
    );
  }

  /// At most three of each: this sits ABOVE the products, and a search for
  /// "s" that pushed every shop and category off the top of the screen would
  /// have answered a question the customer did not ask.
  static const _atMost = 3;

  static List<Storefront> _matchingShops(WidgetRef ref, String term) {
    final pin = ref.watch(deliveryPinProvider);
    if (pin == null) return const [];
    final near = ref.watch(shopsNearProvider(pin)).valueOrNull;
    if (near == null) return const [];
    // THE SERVER'S ORDER IS KEPT. These arrived nearest-first and are filtered,
    // never re-sorted - two shops matching "medical" should be offered in the
    // order the marketplace ranked them.
    return near
        .where((shop) => (shop.displayName ?? '').toLowerCase().contains(term))
        .take(_atMost)
        .toList(growable: false);
  }

  static List<Category> _matchingCategories(WidgetRef ref, String term) {
    final catalogue = ref.watch(categoriesProvider).valueOrNull;
    if (catalogue == null) return const [];
    return catalogue
        .where((category) => category.name.toLowerCase().contains(term))
        .take(_atMost)
        .toList(growable: false);
  }
}

class _Heading extends StatelessWidget {
  const _Heading(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 2),
        child: Text(
          text,
          style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: AppColors.textSecondary),
        ),
      );
}

class _Row extends StatelessWidget {
  const _Row({
    required this.icon,
    required this.label,
    required this.onTap,
    this.detail,
  });

  final IconData icon;
  final String label;
  final String? detail;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: hapticize(onTap),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
        child: Row(
          children: [
            Icon(icon, size: 18, color: AppColors.textSecondary),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w600),
              ),
            ),
            if (detail != null)
              Text(detail!,
                  style: const TextStyle(
                      fontSize: 12, color: AppColors.textSecondary)),
            const SizedBox(width: 4),
            const Icon(Icons.chevron_right, size: 18, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
