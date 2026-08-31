import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
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
        loading: () => const AdminListSkeleton(),
        error: (error, stackTrace) => AdminErrorState(
          // Shows the real failure reason rather than one static string.
          message: "Couldn't load orders: ${extractErrorMessage(error)}",
          onRetry: hapticize(
              () => ref.invalidate(adminCustomerOrdersProvider(customerId))),
        ),
        data: (orders) {
          if (orders.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.receipt_long_outlined,
              title: 'No orders yet',
              message: "This customer hasn't placed an order.",
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
                  decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
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
                                color: order.orderStatus == 'CANCELLED' ? AdminColors.danger : AdminColors.primary,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Text('₹${order.totalAmount.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w700)),
                      const Icon(Icons.chevron_right, color: AdminColors.textSecondary),
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
