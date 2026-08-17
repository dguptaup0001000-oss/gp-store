import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import 'order_detail_screen.dart';
import 'orders_providers.dart';

class OrderHistoryScreen extends ConsumerWidget {
  const OrderHistoryScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ordersAsync = ref.watch(myOrdersProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('My Orders')),
      body: ordersAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string that looks the same whether the cause
              // is a network problem, an auth issue, or something else.
              Text("Couldn't load your orders: ${extractErrorMessage(error)}"),
              const SizedBox(height: 8),
              TextButton(onPressed: () => ref.invalidate(myOrdersProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (page) {
          final orders = page.orders;
          if (orders.isEmpty) {
            return const Center(
              child: Text('No orders yet', style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          final hasMore = ref.read(myOrdersProvider.notifier).hasMore;

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(myOrdersProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: orders.length + (hasMore ? 1 : 0),
              separatorBuilder: (_, __) => const SizedBox(height: 12),
              itemBuilder: (context, index) {
                if (index == orders.length) {
                  WidgetsBinding.instance.addPostFrameCallback(
                    (_) => ref.read(myOrdersProvider.notifier).loadMore(),
                  );
                  return const Center(child: CircularProgressIndicator(strokeWidth: 2));
                }

                final order = orders[index];
                return InkWell(
                  borderRadius: BorderRadius.circular(12),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => OrderDetailScreen(orderId: order.orderId)),
                  ),
                  child: Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: AppColors.cardBackground,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text('#${order.orderNumber}', style: const TextStyle(fontWeight: FontWeight.w700)),
                            _StatusChip(status: order.orderStatus),
                          ],
                        ),
                        const SizedBox(height: 6),
                        Text(
                          _formatDate(order.orderDate),
                          style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12),
                        ),
                        const SizedBox(height: 8),
                        Text('₹${order.totalAmount.toStringAsFixed(0)}',
                            style: const TextStyle(fontWeight: FontWeight.w700)),
                      ],
                    ),
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }

  String _formatDate(String isoDate) {
    try {
      final date = DateTime.parse(isoDate);
      return '${date.day}/${date.month}/${date.year}';
    } catch (_) {
      return isoDate;
    }
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) {
    final isPositive = status != 'CANCELLED';

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: (isPositive ? AppColors.success : AppColors.error).withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        status.replaceAll('_', ' '),
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          color: isPositive ? AppColors.success : AppColors.error,
        ),
      ),
    );
  }
}
