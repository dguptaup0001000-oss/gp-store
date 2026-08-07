import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/brand_avatar.dart';
import '../../products/presentation/brand_products_screen.dart';
import '../../products/presentation/products_providers.dart';

/// Full grid of every store brand (built to comfortably scale to 100+),
/// reached only via the compact "Buy by Brand" banner on the home screen --
/// deliberately not all shown there at once. Has a lightweight client-side
/// name filter since a plain scroll through 100 brands is slow to browse.
class BrandsScreen extends ConsumerStatefulWidget {
  const BrandsScreen({super.key});

  @override
  ConsumerState<BrandsScreen> createState() => _BrandsScreenState();
}

class _BrandsScreenState extends ConsumerState<BrandsScreen> {
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final brandsAsync = ref.watch(brandsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('All Brands', style: TextStyle(fontWeight: FontWeight.w800)),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              controller: _searchController,
              onChanged: (value) => setState(() => _query = value.trim().toLowerCase()),
              decoration: InputDecoration(
                hintText: 'Search brands',
                prefixIcon: const Icon(Icons.search),
                filled: true,
                fillColor: AppColors.cardBackground,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
          ),
          Expanded(
            child: brandsAsync.when(
              loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
              error: (error, stackTrace) => Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text("Couldn't load brands"),
                    const SizedBox(height: 8),
                    TextButton(
                      onPressed: () => ref.invalidate(brandsProvider),
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              ),
              data: (brands) {
                final filtered = _query.isEmpty
                    ? brands
                    : brands.where((b) => b.brand.toLowerCase().contains(_query)).toList();

                if (filtered.isEmpty) {
                  return const Center(child: Text('No brands found'));
                }

                return GridView.builder(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 3,
                    mainAxisSpacing: 20,
                    crossAxisSpacing: 12,
                    childAspectRatio: 0.8,
                  ),
                  itemCount: filtered.length,
                  itemBuilder: (context, index) {
                    final brand = filtered[index];
                    return GestureDetector(
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(builder: (_) => BrandProductsScreen(brand: brand)),
                      ),
                      child: Column(
                        children: [
                          BrandAvatar(brandName: brand.brand),
                          const SizedBox(height: 6),
                          Text(
                            brand.brand,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            textAlign: TextAlign.center,
                            style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
                          ),
                          Text(
                            '${brand.productCount} item${brand.productCount == 1 ? '' : 's'}',
                            style: const TextStyle(fontSize: 10, color: AppColors.textSecondary),
                          ),
                        ],
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
