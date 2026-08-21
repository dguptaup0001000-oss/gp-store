import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/horizontal_product_section.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../reviews/presentation/product_reviews_section.dart';
import '../domain/product_models.dart';
import '../../wishlist/presentation/wishlist_providers.dart';
import 'product_3d_view_screen.dart';
import 'product_image_gallery.dart';
import 'products_providers.dart';

class ProductDetailScreen extends ConsumerStatefulWidget {
  const ProductDetailScreen({super.key, required this.product});

  final Product product;

  @override
  ConsumerState<ProductDetailScreen> createState() => _ProductDetailScreenState();
}

class _ProductDetailScreenState extends ConsumerState<ProductDetailScreen> {
  late ProductVariant? _selectedVariant;
  int _quantity = 1;
  bool _isAdding = false;

  @override
  void initState() {
    super.initState();
    _selectedVariant = widget.product.primaryVariant;
  }

  Future<void> _addToCart() async {
    final variant = _selectedVariant;
    if (variant == null) return;

    setState(() => _isAdding = true);

    try {
      await ref.read(cartControllerProvider.notifier).addToCart(
            variantId: variant.id,
            quantity: _quantity,
          );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('${widget.product.name} added to cart')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isAdding = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final product = widget.product;
    final variant = _selectedVariant;
    final isInStock = variant?.available ?? false;

    return Scaffold(
      appBar: AppBar(
        actions: [
          Consumer(
            builder: (context, ref, _) {
              final isWishlisted = ref.watch(wishlistControllerProvider.notifier).isWishlisted(product.id);
              return IconButton(
                icon: Icon(
                  isWishlisted ? Icons.favorite : Icons.favorite_border,
                  color: isWishlisted ? AppColors.error : null,
                ),
                tooltip: isWishlisted ? 'Remove from wishlist' : 'Add to wishlist',
                onPressed: () => ref.read(wishlistControllerProvider.notifier).toggle(product.id),
              );
            },
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Gallery, assembled from three sources in priority order
                    // so the screen is never empty and never waits:
                    //
                    //   1. the product's own gallery, once the detail request
                    //      lands (list screens do not carry one);
                    //   2. the variant thumbnail the caller already had, shown
                    //      immediately while that request is in flight and kept
                    //      permanently for products with no gallery yet;
                    //   3. a basket placeholder if there is no image at all.
                    //
                    // That ordering is what makes multi-image support additive:
                    // an old single-image product looks exactly as it did.
                    Consumer(
                      builder: (context, ref, _) {
                        final detail = ref.watch(productDetailProvider(product.id));
                        final gallery = detail.valueOrNull?.images ?? const <String>[];
                        final fallback = variant?.imageUrl;

                        final urls = gallery.isNotEmpty
                            ? gallery
                            : (fallback != null ? <String>[fallback] : const <String>[]);

                        return ProductImageGallery(imageUrls: urls);
                      },
                    ),
                    // Directly under the gallery: this is an alternative way
                    // to look at the same thing. Renders nothing at all when
                    // the product has no model, which is almost every product
                    // - see View3dButton for why that is the whole fallback.
                    View3dButton(
                      modelUrl: product.has3dModel ? product.model3dUrl : null,
                      productName: product.name,
                    ),
                    const SizedBox(height: 16),
                    if (product.brand != null)
                      Text(product.brand!, style: Theme.of(context).textTheme.bodyMedium),
                    Text(product.name, style: Theme.of(context).textTheme.headlineSmall),
                    const SizedBox(height: 12),

                    if (product.variants.length > 1) ...[
                      Text('Select size', style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 8,
                        children: product.variants.map((v) {
                          final isSelected = v.id == variant?.id;
                          return ChoiceChip(
                            label: Text('${_formatQty(v.quantity)} ${v.unit ?? ''}'),
                            selected: isSelected,
                            onSelected: (_) => setState(() => _selectedVariant = v),
                          );
                        }).toList(),
                      ),
                      const SizedBox(height: 16),
                    ],

                    if (variant != null)
                      Row(
                        children: [
                          Text(
                            '₹${variant.sellingPrice.toStringAsFixed(0)}',
                            style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 22),
                          ),
                          if (variant.mrp != null && variant.mrp! > variant.sellingPrice) ...[
                            const SizedBox(width: 8),
                            Text(
                              '₹${variant.mrp!.toStringAsFixed(0)}',
                              style: const TextStyle(
                                fontSize: 15,
                                color: AppColors.textSecondary,
                                decoration: TextDecoration.lineThrough,
                              ),
                            ),
                          ],
                        ],
                      ),
                    const SizedBox(height: 4),
                    Text(
                      isInStock ? 'In stock' : 'Out of stock',
                      style: TextStyle(
                        color: isInStock ? AppColors.success : AppColors.error,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 24),
                    HorizontalProductSection(
                      title: 'Frequently Bought Together',
                      provider: ref.watch(frequentlyBoughtTogetherProvider(product.id)),
                      onRetry: () => ref.invalidate(frequentlyBoughtTogetherProvider(product.id)),
                      onProductTap: (p) => Navigator.of(context).pushReplacement(
                        MaterialPageRoute(builder: (_) => ProductDetailScreen(product: p)),
                      ),
                    ),
                    // Same-category products, shown ALONGSIDE frequently-bought
                    // rather than instead of it. Co-purchase data is empty for
                    // most of a young catalogue, so that strip renders blank on
                    // nearly every product; same-category always has something
                    // and is genuinely "similar". Both are cheap - each is one
                    // paged request, and HorizontalProductSection hides itself
                    // when its list comes back empty.
                    if (product.category != null)
                      HorizontalProductSection(
                        title: 'Similar products',
                        provider: ref.watch(similarProductsProvider((
                          categoryId: product.category!.id,
                          excludeProductId: product.id,
                        ))),
                        onRetry: () => ref.invalidate(similarProductsProvider((
                          categoryId: product.category!.id,
                          excludeProductId: product.id,
                        ))),
                        // pushReplacement, not push: tapping through five
                        // similar products in a row would otherwise stack five
                        // detail screens, and Back would walk the customer
                        // through every one of them instead of returning to
                        // where they started browsing.
                        onProductTap: (p) => Navigator.of(context).pushReplacement(
                          MaterialPageRoute(builder: (_) => ProductDetailScreen(product: p)),
                        ),
                      ),
                    const SizedBox(height: 8),
                    ProductReviewsSection(productId: product.id),
                  ],
                ),
              ),
            ),
            // Sits above the Add to Cart bar below, not instead of it - a
            // customer adding a 2nd/3rd item while still browsing this
            // product needs the same "you have items waiting, go check out"
            // shortcut every other browsing screen shows.
            const CartSummaryBar(),
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    Container(
                      decoration: BoxDecoration(
                        border: Border.all(color: AppColors.primary),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Row(
                        children: [
                          IconButton(
                            icon: const Icon(Icons.remove),
                            color: AppColors.primary,
                            tooltip: 'Decrease quantity',
                            onPressed: _quantity > 1 ? () => setState(() => _quantity--) : null,
                          ),
                          Text('$_quantity', style: const TextStyle(fontWeight: FontWeight.w600)),
                          IconButton(
                            icon: const Icon(Icons.add),
                            color: AppColors.primary,
                            tooltip: 'Increase quantity',
                            onPressed: () => setState(() => _quantity++),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: FilledButton(
                        onPressed: (isInStock && !_isAdding) ? _addToCart : null,
                        child: _isAdding
                            ? const SizedBox(
                                height: 20,
                                width: 20,
                                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                              )
                            : Text(isInStock ? 'Add to Cart' : 'Unavailable'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatQty(double? quantity) {
    if (quantity == null) return '';
    return quantity == quantity.roundToDouble() ? quantity.toStringAsFixed(0) : quantity.toStringAsFixed(1);
  }
}
