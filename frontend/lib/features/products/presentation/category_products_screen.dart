import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/widgets/filtered_product_browser.dart';
import '../domain/product_models.dart';
import 'products_providers.dart';

class CategoryProductsScreen extends ConsumerWidget {
  const CategoryProductsScreen({super.key, required this.category});

  final Category category;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: Text(category.name)),
      body: FilteredProductBrowser(
        searchHint: 'Search within ${category.name}',
        fetchPage: ({sort, inStockOnly = false, keyword, required page}) {
          return ref.read(productsRepositoryProvider).browseByCategoryFiltered(
                categoryId: category.id,
                sort: sort,
                inStockOnly: inStockOnly,
                keyword: keyword,
                page: page,
              );
        },
      ),
    );
  }
}
