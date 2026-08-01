import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../orders/domain/order_models.dart';
import 'admin_order_detail_screen.dart';
import 'admin_providers.dart';

class AdminOrderListScreen extends ConsumerStatefulWidget {
  const AdminOrderListScreen({super.key});

  @override
  ConsumerState<AdminOrderListScreen> createState() => _AdminOrderListScreenState();
}

class _AdminOrderListScreenState extends ConsumerState<AdminOrderListScreen> {
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<OrderSummary> _filter(List<OrderSummary> orders) {
    if (_query.isEmpty) return orders;
    final q = _query.toLowerCase();
    return orders.where((o) {
      return o.orderNumber.toLowerCase().contains(q) ||
          (o.customerName?.toLowerCase().contains(q) ?? false);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final ordersAsync = ref.watch(adminAllOrdersProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('All Orders')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              controller: _searchController,
              onChanged: (value) => setState(() => _query = value.trim()),
              decoration: const InputDecoration(
                hintText: 'Search by order number or customer',
                prefixIcon: Icon(Icons.search, size: 20),
              ),
            ),
          ),
          Expanded(
            child: ordersAsync.when(
              loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
              error: (error, stackTrace) => Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text("Couldn't load orders - check your connection"),
                    TextButton(onPressed: () => ref.invalidate(adminAllOrdersProvider), child: const Text('Retry')),
                  ],
                ),
              ),
              data: (allOrders) {
                final orders = _filter(allOrders);

                if (orders.isEmpty) {
                  return Center(
                    child: Text(
                      allOrders.isEmpty ? 'No orders yet' : 'No matches',
                      style: const TextStyle(color: AppColors.textSecondary),
                    ),
                  );
                }

                return RefreshIndicator(
                  onRefresh: () async => ref.invalidate(adminAllOrdersProvider),
                  child: ListView.separated(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                    itemCount: orders.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder: (context, index) {
                      final order = orders[index];
                      return InkWell(
                        borderRadius: BorderRadius.circular(12),
                        onTap: () async {
                          final changed = await Navigator.of(context).push<bool>(
                            MaterialPageRoute(builder: (_) => AdminOrderDetailScreen(orderId: order.orderId)),
                          );
                          if (changed == true) ref.invalidate(adminAllOrdersProvider);
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
                                    Row(
                                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                      children: [
                                        Text('#${order.orderNumber}', style: const TextStyle(fontWeight: FontWeight.w700)),
                                        Text('₹${order.totalAmount.toStringAsFixed(0)}',
                                            style: const TextStyle(fontWeight: FontWeight.w700)),
                                      ],
                                    ),
                                    if (order.customerName != null)
                                      Text(order.customerName!, style: Theme.of(context).textTheme.bodyMedium),
                                    const SizedBox(height: 4),
                                    Text(
                                      order.orderStatus.replaceAll('_', ' '),
                                      style: TextStyle(
                                        fontSize: 11,
                                        fontWeight: FontWeight.w600,
                                        color: order.orderStatus == 'CANCELLED' ? AppColors.error : AppColors.primary,
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
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
