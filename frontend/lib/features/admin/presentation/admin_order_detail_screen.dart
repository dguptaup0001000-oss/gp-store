import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../orders/domain/order_models.dart';
import '../../orders/presentation/orders_providers.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

/// Reuses orderDetailProvider directly - the backend's GET /api/orders/{id}
/// now lets an admin bypass the ownership check on the SAME endpoint the
/// customer screen uses, so there's no need for a separate admin-only
/// detail call. What's admin-specific here is purely the ability to change
/// status - a customer never gets that control on their own order screen.
class AdminOrderDetailScreen extends ConsumerStatefulWidget {
  const AdminOrderDetailScreen({super.key, required this.orderId});

  final int orderId;

  @override
  ConsumerState<AdminOrderDetailScreen> createState() => _AdminOrderDetailScreenState();
}

class _AdminOrderDetailScreenState extends ConsumerState<AdminOrderDetailScreen> {
  bool _isUpdating = false;

  static const _statusOptions = [
    'PENDING_CONFIRMATION',
    'CONFIRMED',
    'PACKING',
    'READY_TO_DISPATCH',
    'OUT_FOR_DELIVERY',
    'DELIVERED',
    'CANCELLED',
  ];

  Future<void> _updateStatus(String newStatus) async {
    setState(() => _isUpdating = true);

    try {
      await ref.read(adminProductsRepositoryProvider).updateOrderStatus(
            orderId: widget.orderId,
            status: newStatus,
          );
      ref.invalidate(orderDetailProvider(widget.orderId));
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Status updated to ${newStatus.replaceAll('_', ' ')}')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isUpdating = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final orderAsync = ref.watch(orderDetailProvider(widget.orderId));

    return Scaffold(
      appBar: AppBar(title: const Text('Order Detail')),
      body: orderAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load this order: ${extractErrorMessage(error)}"),
              TextButton(
                onPressed: hapticize(() => ref.invalidate(orderDetailProvider(widget.orderId))),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
        data: (order) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text('#${order.orderNumber}', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),

            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Order Status', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 8),
                  DropdownButtonFormField<String>(
                    initialValue: _statusOptions.contains(order.orderStatus) ? order.orderStatus : null,
                    items: _statusOptions
                        .map((s) => DropdownMenuItem(value: s, child: Text(s.replaceAll('_', ' '))))
                        .toList(),
                    onChanged: hapticizeValue(_isUpdating ? null : (value) => value == null ? null : _updateStatus(value)),
                  ),
                  if (_isUpdating) ...[
                    const SizedBox(height: 8),
                    const LinearProgressIndicator(),
                  ],
                ],
              ),
            ),

            const SizedBox(height: 16),
            // A Delivery record can't exist without a partner already
            // assigned in this data model - null here means auto-assignment
            // at order placement found no available partner and this needs
            // manual assignment (see DeliveryService.autoAssignBestEffort).
            if (order.delivery == null) _DeliveryAssignmentCard(orderId: widget.orderId),

            const SizedBox(height: 16),
            if (order.address != null) ...[
              Text('Delivery Address', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(order.address!.fullName, style: const TextStyle(fontWeight: FontWeight.w700)),
                    const SizedBox(height: 4),
                    Text(order.address!.fullAddress, style: Theme.of(context).textTheme.bodyMedium),
                    const SizedBox(height: 4),
                    Text(order.address!.mobileNumber, style: Theme.of(context).textTheme.bodyMedium),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],

            Text('Items', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            ...order.items.map((item) => _AdminOrderItemTile(item: item)),

            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('Total', style: TextStyle(fontWeight: FontWeight.w700)),
                  Text('₹${order.totalAmount.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w700)),
                ],
              ),
            ),
            const SizedBox(height: 4),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text('Payment: ${order.paymentStatus.replaceAll('_', ' ')}',
                  style: Theme.of(context).textTheme.bodyMedium),
            ),
          ],
        ),
      ),
    );
  }
}

class _DeliveryAssignmentCard extends ConsumerStatefulWidget {
  const _DeliveryAssignmentCard({required this.orderId});

  final int orderId;

  @override
  ConsumerState<_DeliveryAssignmentCard> createState() => _DeliveryAssignmentCardState();
}

class _DeliveryAssignmentCardState extends ConsumerState<_DeliveryAssignmentCard> {
  int? _selectedPartnerId;
  bool _isAssigning = false;

  Future<void> _assign() async {
    if (_selectedPartnerId == null) return;

    setState(() => _isAssigning = true);

    try {
      await ref.read(adminProductsRepositoryProvider).assignDeliveryPartner(
            orderId: widget.orderId,
            deliveryPartnerId: _selectedPartnerId!,
          );
      ref.invalidate(orderDetailProvider(widget.orderId));
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Delivery partner assigned')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _isAssigning = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final partnersAsync = ref.watch(adminAvailablePartnersProvider);

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.error.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.error.withValues(alpha: 0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'No delivery partner assigned',
            style: TextStyle(fontWeight: FontWeight.w700, color: AppColors.error),
          ),
          const SizedBox(height: 4),
          const Text(
            'Auto-assignment found no available partner when this order was placed. Assign one manually below.',
            style: TextStyle(fontSize: 12),
          ),
          const SizedBox(height: 12),
          partnersAsync.when(
            loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
            error: (error, stackTrace) => Text("Couldn't load delivery partners: ${extractErrorMessage(error)}"),
            data: (allPartners) {
              // id is nullable on the model in general (a not-yet-saved
              // partner has none) but every entry from this specific
              // "available partners" endpoint is a real, persisted row -
              // filtering rather than assuming keeps the dropdown's typing
              // honestly non-null instead of force-unwrapping.
              final partners = allPartners.where((p) => p.id != null).toList();

              if (partners.isEmpty) {
                return const Text('No delivery partners are currently available either.');
              }

              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  DropdownButtonFormField<int>(
                    initialValue: _selectedPartnerId,
                    decoration: const InputDecoration(labelText: 'Assign to'),
                    items: partners
                        .map((p) => DropdownMenuItem(value: p.id!, child: Text('${p.name} (${p.vehicleType})')))
                        .toList(),
                    onChanged: hapticizeValue((value) => setState(() => _selectedPartnerId = value)),
                  ),
                  const SizedBox(height: 8),
                  FilledButton(
                    onPressed: (_selectedPartnerId == null || _isAssigning) ? null : _assign,
                    child: _isAssigning
                        ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Assign'),
                  ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }
}

class _AdminOrderItemTile extends StatelessWidget {
  const _AdminOrderItemTile({required this.item});

  final OrderItemDetail item;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          Expanded(
            child: Text(item.productName ?? 'Product', style: const TextStyle(fontSize: 13)),
          ),
          Text('Qty ${item.quantity}', style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12)),
          const SizedBox(width: 12),
          Text('₹${item.totalPrice.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }
}
