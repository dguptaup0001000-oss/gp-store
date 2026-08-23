import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import 'admin_order_detail_screen.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminCustomerOrdersScreen extends ConsumerWidget {
  const AdminCustomerOrdersScreen({super.key, required this.customerId, required this.customerName});

  final int customerId;
  final String customerName;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ordersAsync = ref.watch(adminCustomerOrdersProvider(customerId));

    return Scaffold(
      appBar: AppBar(title: Text("$customerName's Orders")),
      body: ordersAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load orders: ${extractErrorMessage(error)}"),
              const SizedBox(height: 8),
              TextButton(
                onPressed: hapticize(() => ref.invalidate(adminCustomerOrdersProvider(customerId))),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
        data: (orders) {
          if (orders.isEmpty) {
            return const Center(
              child: Text("This customer hasn't placed any orders yet", style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: orders.length,
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, index) {
              final order = orders[index];
              return InkWell(
                borderRadius: BorderRadius.circular(12),
                onTap: hapticize(() => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => AdminOrderDetailScreen(orderId: order.orderId)),
                )),
                child: Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('#${order.orderNumber}', style: const TextStyle(fontWeight: FontWeight.w700)),
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
                      Text('₹${order.totalAmount.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w700)),
                      const Icon(Icons.chevron_right, color: AppColors.textSecondary),
                    ],
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }
}
