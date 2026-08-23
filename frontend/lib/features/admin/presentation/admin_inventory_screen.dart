import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/inventory_models.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminInventoryScreen extends StatelessWidget {
  const AdminInventoryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Inventory'),
          bottom: const TabBar(tabs: [Tab(text: 'Low Stock'), Tab(text: 'All Inventory')]),
        ),
        body: const TabBarView(
          children: [
            _LowStockList(),
            _AllInventoryList(),
          ],
        ),
      ),
    );
  }
}

/// Low stock is naturally self-bounded (only items at or below their
/// reorder point - a real business-bounded list, not "every item ever
/// stocked"), so this stays a plain single-shot fetch, unlike the paginated
/// list below.
class _LowStockList extends ConsumerWidget {
  const _LowStockList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final itemsAsync = ref.watch(adminLowStockProvider);

    return itemsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, stackTrace) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text("Couldn't load inventory: ${extractErrorMessage(error)}"),
            TextButton(onPressed: hapticize(() => ref.invalidate(adminLowStockProvider)), child: const Text('Retry')),
          ],
        ),
      ),
      data: (items) {
        if (items.isEmpty) {
          return const Center(
            child: Text('Nothing is low on stock right now', style: TextStyle(color: AppColors.textSecondary)),
          );
        }

        return RefreshIndicator(
          onRefresh: () async => ref.invalidate(adminLowStockProvider),
          child: ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: items.length,
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, index) => _InventoryTile(item: items[index]),
          ),
        );
      },
    );
  }
}

/// Real pagination - every inventory row ever created has no natural upper
/// bound, unlike the low-stock list above. Loads more as the admin scrolls
/// to the bottom, same pattern as AdminOrderListScreen.
class _AllInventoryList extends ConsumerWidget {
  const _AllInventoryList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pageAsync = ref.watch(adminAllInventoryProvider);
    final controller = ref.read(adminAllInventoryProvider.notifier);

    return pageAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, stackTrace) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text("Couldn't load inventory: ${extractErrorMessage(error)}"),
            TextButton(onPressed: hapticize(() => ref.invalidate(adminAllInventoryProvider)), child: const Text('Retry')),
          ],
        ),
      ),
      data: (result) {
        final items = result.items;
        if (items.isEmpty) {
          return const Center(
            child: Text('No inventory records yet', style: TextStyle(color: AppColors.textSecondary)),
          );
        }

        final hasMore = controller.hasMore;

        return RefreshIndicator(
          onRefresh: () async => ref.invalidate(adminAllInventoryProvider),
          child: ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: items.length + (hasMore ? 1 : 0),
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, index) {
              if (index == items.length) {
                WidgetsBinding.instance.addPostFrameCallback((_) => controller.loadMore());
                return const Center(child: CircularProgressIndicator(strokeWidth: 2));
              }
              return _InventoryTile(item: items[index]);
            },
          ),
        );
      },
    );
  }
}

class _InventoryTile extends ConsumerWidget {
  const _InventoryTile({required this.item});

  final InventoryItem item;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.productName ?? 'Unknown product', style: const TextStyle(fontWeight: FontWeight.w700)),
                if (item.productBrand != null)
                  Text(item.productBrand!, style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: 4),
                Text(
                  'Stock: ${item.availableStock} available'
                  '${(item.reservedStock ?? 0) > 0 ? ' (${item.reservedStock} reserved)' : ''}',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: item.isLowStock ? AppColors.error : AppColors.textSecondary,
                  ),
                ),
                if (item.minimumStock != null)
                  Text('Reorder point: ${item.minimumStock}',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11)),
              ],
            ),
          ),
          Column(
            children: [
              IconButton(
                icon: const Icon(Icons.add_box_outlined, color: AppColors.primary),
                tooltip: 'Restock',
                onPressed: hapticize(() => _showRestockDialog(context, ref)),
              ),
              IconButton(
                icon: const Icon(Icons.edit_outlined),
                tooltip: 'Manual correction',
                onPressed: hapticize(() => _showEditDialog(context, ref)),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _showRestockDialog(BuildContext context, WidgetRef ref) async {
    final controller = TextEditingController();

    final quantity = await showDialog<int>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Restock: ${item.productName ?? "item"}'),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'How many units received?'),
        ),
        actions: [
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop()), child: const Text('Cancel')),
          FilledButton(
            onPressed: hapticize(() => Navigator.of(context).pop(int.tryParse(controller.text.trim()))),
            child: const Text('Add Stock'),
          ),
        ],
      ),
    );

    // Disposed as soon as the dialog is gone. A TextEditingController is a
    // ChangeNotifier; one created per dialog open and never released is a
    // small leak that accumulates across a stock-take session, when this
    // dialog is opened dozens of times in a row.
    controller.dispose();

    if (quantity == null || quantity <= 0) return;

    try {
      await ref.read(adminProductsRepositoryProvider).restock(inventoryId: item.id, quantity: quantity);
      ref.invalidate(adminAllInventoryProvider);
      ref.invalidate(adminLowStockProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }

  Future<void> _showEditDialog(BuildContext context, WidgetRef ref) async {
    final stockController = TextEditingController(text: item.stock?.toString() ?? '0');
    final minController = TextEditingController(text: item.minimumStock?.toString() ?? '');
    final maxController = TextEditingController(text: item.maximumStock?.toString() ?? '');

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Correct: ${item.productName ?? "item"}'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'For stock-take corrections, not routine restocking.',
              style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: stockController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Actual stock count'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: minController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Reorder point (minimum)'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: maxController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Maximum stock (optional)'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop(false)), child: const Text('Cancel')),
          FilledButton(onPressed: hapticize(() => Navigator.of(context).pop(true)), child: const Text('Save')),
        ],
      ),
    );

    // Values read BEFORE disposing - the controllers are still needed here,
    // unlike the restock dialog above where the parse happens inside the
    // action. Read, then release, then use.
    final stock = int.tryParse(stockController.text.trim());
    final minStock = int.tryParse(minController.text.trim());
    final maxStockText = maxController.text.trim();

    stockController.dispose();
    minController.dispose();
    maxController.dispose();

    if (confirmed != true) return;
    if (stock == null || minStock == null) return;

    try {
      await ref.read(adminProductsRepositoryProvider).updateInventory(
            inventoryId: item.id,
            stock: stock,
            minimumStock: minStock,
            maximumStock: int.tryParse(maxStockText),
            // Passed through unchanged - see updateInventory()'s doc comment
            // for why this must never be omitted.
            currentReservedStock: item.reservedStock,
          );
      ref.invalidate(adminAllInventoryProvider);
      ref.invalidate(adminLowStockProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }
}
