import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_format.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart';
import 'admin_product_form_screen.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

/// The catalogue.
///
/// SEARCH, EDIT AND THE ADD BUTTON ARE UNCHANGED. The redesign adds the
/// thumbnail an operator uses to recognise a product at a glance, turns the
/// two warnings that used to be loose red text into proper badges, and puts
/// the price range on the row - because "which of the six atta products is
/// the 5kg one" is the question this screen exists to answer.
class AdminProductListScreen extends ConsumerStatefulWidget {
  const AdminProductListScreen({super.key});

  @override
  ConsumerState<AdminProductListScreen> createState() => _AdminProductListScreenState();
}

class _AdminProductListScreenState extends ConsumerState<AdminProductListScreen> {
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<Product> _filter(List<Product> products) {
    if (_query.isEmpty) return products;
    final q = _query.toLowerCase();
    return products.where((p) {
      return p.name.toLowerCase().contains(q) || (p.brand?.toLowerCase().contains(q) ?? false);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final productsAsync = ref.watch(adminAllProductsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Products')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
              AdminSpacing.lg,
              AdminSpacing.lg,
              AdminSpacing.lg,
              AdminSpacing.md,
            ),
            child: TextField(
              controller: _searchController,
              onChanged: (value) => setState(() => _query = value.trim()),
              decoration: InputDecoration(
                hintText: 'Search by name or brand',
                prefixIcon: const Icon(Icons.search, size: 20),
                suffixIcon: _query.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.close, size: 18),
                        tooltip: 'Clear',
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _query = '');
                        },
                      ),
              ),
            ),
          ),
          Expanded(
            child: productsAsync.when(
              loading: () => const _ProductListSkeleton(),
              error: (error, stackTrace) => AdminErrorState(
                // Shows the real failure reason rather than one static
                // string - an admin who can read the cause can act on it.
                message: "Couldn't load products: ${extractErrorMessage(error)}",
                onRetry:
                    hapticize(() => ref.invalidate(adminAllProductsProvider)),
              ),
              data: (allProducts) {
                final products = _filter(allProducts);

                if (products.isEmpty) {
                  return allProducts.isEmpty
                      ? const AdminEmptyState(
                          icon: Icons.inventory_2_outlined,
                          title: 'No products yet',
                          message:
                              'Tap Add Product to put the first item in your catalogue.',
                        )
                      : AdminEmptyState(
                          icon: Icons.search_off_outlined,
                          title: 'No matching products',
                          message:
                              'Nothing matches "$_query". Try a shorter search.',
                          action: TextButton(
                            onPressed: hapticize(() {
                              _searchController.clear();
                              setState(() => _query = '');
                            }),
                            child: const Text('Clear search'),
                          ),
                        );
                }

                return RefreshIndicator(
                  color: AdminColors.primary,
                  onRefresh: () async =>
                      ref.invalidate(adminAllProductsProvider),
                  child: ListView.separated(
                    padding: const EdgeInsets.fromLTRB(
                      AdminSpacing.lg,
                      0,
                      AdminSpacing.lg,
                      // Room for the FAB, which would otherwise sit on top of
                      // the last row and hide whatever it says.
                      80,
                    ),
                    itemCount: products.length,
                    separatorBuilder: (_, __) =>
                        const SizedBox(height: AdminSpacing.sm),
                    itemBuilder: (context, index) =>
                        _ProductTile(product: products[index]),
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: hapticize(() async {
          final saved = await Navigator.of(context).push<bool>(
            MaterialPageRoute(builder: (_) => const AdminProductFormScreen()),
          );
          if (saved == true) ref.invalidate(adminAllProductsProvider);
        }),
        icon: const Icon(Icons.add),
        label: const Text('Add Product'),
      ),
    );
  }
}

class _ProductTile extends ConsumerWidget {
  const _ProductTile({required this.product});

  final Product product;

  /// The cheapest and dearest sellable variant, as one label.
  ///
  /// Returns null when there is nothing to price, so the row shows the
  /// "no variants" warning instead of an empty or zero rupee figure.
  String? _priceLabel() {
    if (product.variants.isEmpty) return null;
    var low = product.variants.first.sellingPrice;
    var high = low;
    for (final variant in product.variants) {
      if (variant.sellingPrice < low) low = variant.sellingPrice;
      if (variant.sellingPrice > high) high = variant.sellingPrice;
    }
    return low == high
        ? AdminFormat.rupees(low)
        : '${AdminFormat.rupees(low)} - ${AdminFormat.rupees(high)}';
  }

  String? _thumbnail() {
    for (final variant in product.variants) {
      final url = variant.imageUrl;
      if (url != null && url.isNotEmpty) return url;
    }
    return null;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final variantCount = product.variants.length;
    final price = _priceLabel();

    return Material(
      color: AdminColors.surface,
      borderRadius: AdminRadius.card,
      child: InkWell(
        borderRadius: AdminRadius.card,
        onTap: hapticize(() async {
          final saved = await Navigator.of(context).push<bool>(
            MaterialPageRoute(
                builder: (_) => AdminProductFormScreen(product: product)),
          );
          if (saved == true) ref.invalidate(adminAllProductsProvider);
        }),
        child: Container(
          padding: const EdgeInsets.all(AdminSpacing.md),
          decoration: BoxDecoration(
            borderRadius: AdminRadius.card,
            border: Border.all(color: AdminColors.border),
            boxShadow: AdminShadows.card,
          ),
          child: Row(
            children: [
              _Thumbnail(url: _thumbnail()),
              const SizedBox(width: AdminSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      product.name,
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        color: AdminColors.textPrimary,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (product.brand != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        product.brand!,
                        style: AdminText.caption,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                    const SizedBox(height: AdminSpacing.sm),
                    Wrap(
                      spacing: AdminSpacing.sm,
                      runSpacing: AdminSpacing.xs,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        if (!product.active)
                          const AdminStatusBadge(
                            label: 'Inactive',
                            tone: AdminStatusTone.danger,
                            dense: true,
                          ),
                        // A product with no variant cannot be bought. That is
                        // a blocking problem, not a note, so it gets the same
                        // weight as "inactive" rather than grey body text.
                        if (variantCount == 0)
                          const AdminStatusBadge(
                            label: 'Not sellable - no variants',
                            tone: AdminStatusTone.warning,
                            dense: true,
                          )
                        else
                          Text(
                            '$variantCount variant${variantCount == 1 ? '' : 's'}',
                            style: AdminText.caption,
                          ),
                        if (price != null)
                          Text(price, style: AdminText.numeric),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(width: AdminSpacing.sm),
              const Icon(Icons.chevron_right,
                  color: AdminColors.textMuted, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

class _Thumbnail extends StatelessWidget {
  const _Thumbnail({required this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    final source = url;
    return ClipRRect(
      borderRadius: BorderRadius.circular(AdminRadius.md),
      child: SizedBox(
        width: 48,
        height: 48,
        child: source == null
            ? const ColoredBox(
                color: AdminColors.neutralBg,
                child: Icon(Icons.image_not_supported_outlined,
                    size: 20, color: AdminColors.textMuted),
              )
            : Image.network(
                source,
                fit: BoxFit.cover,
                // A photograph that fails to load must never take the
                // catalogue with it: these are short-lived signed URLs and
                // one can expire while the screen is open.
                errorBuilder: (_, __, ___) => const ColoredBox(
                  color: AdminColors.neutralBg,
                  child: Icon(Icons.broken_image_outlined,
                      size: 20, color: AdminColors.textMuted),
                ),
              ),
      ),
    );
  }
}

class _ProductListSkeleton extends StatelessWidget {
  const _ProductListSkeleton();

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(
        AdminSpacing.lg,
        0,
        AdminSpacing.lg,
        AdminSpacing.lg,
      ),
      itemCount: 7,
      separatorBuilder: (_, __) => const SizedBox(height: AdminSpacing.sm),
      itemBuilder: (_, __) => Container(
        padding: const EdgeInsets.all(AdminSpacing.md),
        decoration: BoxDecoration(
          color: AdminColors.surface,
          borderRadius: AdminRadius.card,
          border: Border.all(color: AdminColors.border),
        ),
        child: const Row(
          children: [
            AdminSkeleton(height: 48, width: 48, radius: AdminRadius.md),
            SizedBox(width: AdminSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  AdminSkeleton(height: 14, width: 160),
                  SizedBox(height: AdminSpacing.sm),
                  AdminSkeleton(height: 10, width: 100),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
