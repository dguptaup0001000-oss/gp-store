import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart';
import 'admin_product_form_screen.dart';
import 'admin_providers.dart';

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
            padding: const EdgeInsets.all(16),
            child: TextField(
              controller: _searchController,
              onChanged: (value) => setState(() => _query = value.trim()),
              decoration: const InputDecoration(
                hintText: 'Search by name or brand',
                prefixIcon: Icon(Icons.search, size: 20),
              ),
            ),
          ),
          Expanded(
            child: productsAsync.when(
              loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
              error: (error, stackTrace) => Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // TEMPORARY, for active debugging - see RootScreen's identical
                    // comment for why this shows the real failure reason instead
                    // of one static string.
                    Text("Couldn't load products: ${extractErrorMessage(error)}"),
                    TextButton(onPressed: () => ref.invalidate(adminAllProductsProvider), child: const Text('Retry')),
                  ],
                ),
              ),
              data: (allProducts) {
                final products = _filter(allProducts);

                if (products.isEmpty) {
                  return Center(
                    child: Text(
                      allProducts.isEmpty ? 'No products yet - tap + to add one' : 'No matches',
                      style: const TextStyle(color: AppColors.textSecondary),
                    ),
                  );
                }

                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  itemCount: products.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, index) => _ProductTile(product: products[index]),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          final saved = await Navigator.of(context).push<bool>(
            MaterialPageRoute(builder: (_) => const AdminProductFormScreen()),
          );
          if (saved == true) ref.invalidate(adminAllProductsProvider);
        },
        icon: const Icon(Icons.add),
        label: const Text('Add Product'),
      ),
    );
  }
}

class _ProductTile extends ConsumerWidget {
  const _ProductTile({required this.product});

  final Product product;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final variantCount = product.variants.length;

    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () async {
        final saved = await Navigator.of(context).push<bool>(
          MaterialPageRoute(builder: (_) => AdminProductFormScreen(product: product)),
        );
        if (saved == true) ref.invalidate(adminAllProductsProvider);
      },
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(product.name, style: const TextStyle(fontWeight: FontWeight.w700)),
                  if (product.brand != null)
                    Text(product.brand!, style: Theme.of(context).textTheme.bodyMedium),
                  if (!product.active)
                    const Padding(
                      padding: EdgeInsets.only(top: 2),
                      child: Text('Inactive', style: TextStyle(color: AppColors.error, fontSize: 11, fontWeight: FontWeight.w600)),
                    ),
                  const SizedBox(height: 4),
                  Text(
                    variantCount == 0 ? 'No variants yet - add one to make this sellable' : '$variantCount variant(s)',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          fontSize: 12,
                          color: variantCount == 0 ? AppColors.error : AppColors.textSecondary,
                        ),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
