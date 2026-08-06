import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/horizontal_product_section.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../reviews/presentation/product_reviews_section.dart';
import '../domain/product_models.dart';
import '../../wishlist/presentation/wishlist_providers.dart';
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
                    AspectRatio(
                      aspectRatio: 1,
                      child: Container(
                        decoration: BoxDecoration(
                          color: AppColors.cardBackground,
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: variant?.imageUrl != null
                            ? ClipRRect(
                                borderRadius: BorderRadius.circular(12),
                                child: Image.network(
                                  variant!.imageUrl!,
                                  fit: BoxFit.contain,
                                  errorBuilder: (context, error, stackTrace) => const Icon(
                                    Icons.image_not_supported_outlined,
                                    size: 48,
                                    color: AppColors.textSecondary,
                                  ),
                                ),
                              )
                            : const Icon(Icons.shopping_basket_outlined, size: 48, color: AppColors.textSecondary),
                      ),
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
                    const SizedBox(height: 8),
                    ProductReviewsSection(productId: product.id),
                  ],
                ),
              ),
            ),
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
