import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../domain/live_tracking_model.dart';
import '../domain/order_models.dart';
import 'invoice_screen.dart';
import 'orders_providers.dart';

class OrderDetailScreen extends ConsumerWidget {
  const OrderDetailScreen({super.key, required this.orderId});

  final int orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final orderAsync = ref.watch(orderDetailProvider(orderId));

    return Scaffold(
      appBar: AppBar(title: const Text('Order Details')),
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
                onPressed: () => ref.invalidate(orderDetailProvider(orderId)),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
        data: (order) => _OrderDetailBody(order: order),
      ),
    );
  }
}

class _OrderDetailBody extends ConsumerStatefulWidget {
  const _OrderDetailBody({required this.order});

  final OrderDetail order;

  @override
  ConsumerState<_OrderDetailBody> createState() => _OrderDetailBodyState();
}

class _OrderDetailBodyState extends ConsumerState<_OrderDetailBody> {
  bool _isCancelling = false;
  bool _isBuyingAgain = false;

  Future<void> _buyAgain() async {
    final items = widget.order.items;

    final reorderable = items.where((item) => item.variantId != null && item.currentlyAvailable == true).toList();
    final skippedCount = items.length - reorderable.length;

    if (reorderable.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('None of these items are available anymore')),
      );
      return;
    }

    setState(() => _isBuyingAgain = true);

    // Sequential, not parallel - each call mutates the same cart, and
    // running them concurrently risks the cart's own state updates racing
    // against each other.
    var addedCount = 0;
    var failedCount = 0;
    for (final item in reorderable) {
      try {
        await ref.read(cartControllerProvider.notifier).addToCart(
              variantId: item.variantId!,
              quantity: item.quantity,
            );
        addedCount++;
      } catch (e) {
        failedCount++;
      }
    }

    if (!mounted) return;
    setState(() => _isBuyingAgain = false);

    final messageParts = <String>['$addedCount item${addedCount == 1 ? '' : 's'} added to cart'];
    if (skippedCount > 0) messageParts.add('$skippedCount no longer available');
    if (failedCount > 0) messageParts.add('$failedCount failed');

    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(messageParts.join(', '))));
  }

  Future<void> _cancelOrder() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Cancel this order?'),
        content: const Text('This cannot be undone.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('No')),
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Yes, cancel')),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _isCancelling = true);

    try {
      await ref.read(ordersRepositoryProvider).cancelOrder(widget.order.orderId);
      ref.invalidate(orderDetailProvider(widget.order.orderId));
      ref.invalidate(myOrdersProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Order cancelled')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isCancelling = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final order = widget.order;

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('#${order.orderNumber}', style: Theme.of(context).textTheme.titleLarge),
            _StatusBadge(status: order.orderStatus),
          ],
        ),
        const SizedBox(height: 16),

        if (order.delivery != null) _DeliveryTrackingCard(delivery: order.delivery!, orderId: order.orderId),

        const SizedBox(height: 16),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('Items', style: Theme.of(context).textTheme.titleMedium),
            TextButton.icon(
              onPressed: _isBuyingAgain ? null : _buyAgain,
              icon: _isBuyingAgain
                  ? const SizedBox(height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.replay, size: 16),
              label: const Text('Buy Again'),
            ),
          ],
        ),
        const SizedBox(height: 8),
        ...order.items.map((item) => _OrderItemTile(item: item)),

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

        Text('Bill Details', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
          child: Column(
            children: [
              if (order.discountAmount != null && order.discountAmount! > 0)
                _billRow('Discount${order.appliedCouponCode != null ? ' (${order.appliedCouponCode})' : ''}',
                    '-₹${order.discountAmount!.toStringAsFixed(0)}'),
              if (order.deliveryFee != null)
                _billRow('Delivery Fee',
                    order.deliveryFee == 0 ? 'FREE' : '₹${order.deliveryFee!.toStringAsFixed(0)}'),
              const Divider(height: 20),
              _billRow('Total Paid', '₹${order.totalAmount.toStringAsFixed(0)}', bold: true),
              const SizedBox(height: 4),
              Text('Payment: ${order.paymentStatus.replaceAll('_', ' ')}',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12)),
            ],
          ),
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          onPressed: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => InvoiceScreen(orderId: order.orderId)),
          ),
          icon: const Icon(Icons.receipt_outlined, size: 18),
          label: const Text('View Invoice'),
        ),

        if (order.isCancellable) ...[
          const SizedBox(height: 24),
          OutlinedButton(
            onPressed: _isCancelling ? null : _cancelOrder,
            style: OutlinedButton.styleFrom(foregroundColor: AppColors.error, side: const BorderSide(color: AppColors.error)),
            child: _isCancelling
                ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Cancel Order'),
          ),
        ],
        const SizedBox(height: 24),
      ],
    );
  }

  Widget _billRow(String label, String value, {bool bold = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(fontWeight: bold ? FontWeight.w700 : FontWeight.w400)),
          Text(value, style: TextStyle(fontWeight: bold ? FontWeight.w700 : FontWeight.w400)),
        ],
      ),
    );
  }
}

class _DeliveryTrackingCard extends ConsumerStatefulWidget {
  const _DeliveryTrackingCard({required this.delivery, required this.orderId});

  final DeliveryTrackingInfo delivery;
  final int orderId;

  @override
  ConsumerState<_DeliveryTrackingCard> createState() => _DeliveryTrackingCardState();
}

class _DeliveryTrackingCardState extends ConsumerState<_DeliveryTrackingCard> {
  Timer? _pollTimer;

  @override
  void initState() {
    super.initState();
    // Only worth polling while there's an actual partner en route - no
    // point hitting the API every 15s for an order still being prepared,
    // or one already delivered/cancelled.
    if (widget.delivery.deliveryStatus == 'OUT_FOR_DELIVERY') {
      _pollTimer = Timer.periodic(const Duration(seconds: 15), (_) {
        ref.invalidate(liveTrackingProvider(widget.orderId));
      });
    }
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  Future<void> _navigateToPartner(double lat, double lng) async {
    await launchUrl(
      Uri.parse('https://www.google.com/maps/dir/?api=1&destination=$lat,$lng'),
      mode: LaunchMode.externalApplication,
    );
  }

  String _timeAgo(String iso) {
    try {
      final updated = DateTime.parse(iso);
      final seconds = DateTime.now().difference(updated).inSeconds;
      if (seconds < 60) return 'updated ${seconds}s ago';
      final minutes = seconds ~/ 60;
      return 'updated ${minutes}m ago';
    } catch (_) {
      return '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final delivery = widget.delivery;
    final isOutForDelivery = delivery.deliveryStatus == 'OUT_FOR_DELIVERY';

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.local_shipping_outlined, color: AppColors.primary),
              const SizedBox(width: 8),
              Text(
                delivery.deliveryStatus?.replaceAll('_', ' ') ?? 'Preparing your order',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ],
          ),
          if (delivery.estimatedDeliveryTime != null) ...[
            const SizedBox(height: 8),
            Text(
              'Estimated: ${_formatTime(delivery.estimatedDeliveryTime!)}',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ],
          if (delivery.deliveryPersonName != null) ...[
            const SizedBox(height: 8),
            Text('Delivery partner: ${delivery.deliveryPersonName}', style: Theme.of(context).textTheme.bodyMedium),
          ],

          // Live GPS section - only shown once the order is actually out for
          // delivery. Before that there's nothing to track yet.
          if (isOutForDelivery) ...[
            const SizedBox(height: 10),
            Consumer(
              builder: (context, ref, _) {
                final liveAsync = ref.watch(liveTrackingProvider(widget.orderId));

                return liveAsync.when(
                  loading: () => const Padding(
                    padding: EdgeInsets.symmetric(vertical: 4),
                    child: SizedBox(height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2)),
                  ),
                  // Silent on error - this is a background poll, not the
                  // main content of the screen. The status/ETA above still
                  // shows fine even if a single poll fails.
                  error: (error, stackTrace) => const SizedBox.shrink(),
                  data: (live) {
                    if (!live.hasLocation) {
                      return Text(
                        "Waiting for your delivery partner's location...",
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: AppColors.textSecondary),
                      );
                    }

                    return Row(
                      children: [
                        Expanded(
                          child: Text(
                            live.locationUpdatedAt != null ? _timeAgo(live.locationUpdatedAt!) : 'Live location available',
                            style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: AppColors.textSecondary, fontSize: 12),
                          ),
                        ),
                        OutlinedButton.icon(
                          onPressed: () => _navigateToPartner(live.partnerLatitude!, live.partnerLongitude!),
                          icon: const Icon(Icons.map_outlined, size: 16),
                          label: const Text('View on map'),
                        ),
                      ],
                    );
                  },
                );
              },
            ),
          ],

          if (delivery.guaranteeBreached == true) ...[
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.error.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                children: [
                  const Icon(Icons.info_outline, size: 16, color: AppColors.error),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      "This delivery took longer than our promised time - we're sorry about that.",
                      style: const TextStyle(fontSize: 12, color: AppColors.error, fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  String _formatTime(String iso) {
    try {
      final date = DateTime.parse(iso);
      return '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}, ${date.day}/${date.month}';
    } catch (_) {
      return iso;
    }
  }
}

class _OrderItemTile extends StatelessWidget {
  const _OrderItemTile({required this.item});

  final OrderItemDetail item;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(8)),
            child: item.imageUrl != null
                ? ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.network(
                      item.imageUrl!,
                      fit: BoxFit.contain,
                      errorBuilder: (context, error, stackTrace) =>
                          const Icon(Icons.image_not_supported_outlined, size: 18, color: AppColors.textSecondary),
                    ),
                  )
                : const Icon(Icons.shopping_basket_outlined, size: 18, color: AppColors.textSecondary),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.productName ?? 'Product', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
                Text('Qty ${item.quantity}', style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12)),
                if (item.currentlyAvailable == false)
                  const Text(
                    'No longer available',
                    style: TextStyle(fontSize: 11, color: AppColors.error, fontWeight: FontWeight.w600),
                  ),
              ],
            ),
          ),
          Text('₹${item.totalPrice.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) {
    final isPositive = status != 'CANCELLED';

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: (isPositive ? AppColors.success : AppColors.error).withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        status.replaceAll('_', ' '),
        style: TextStyle(fontWeight: FontWeight.w700, fontSize: 11, color: isPositive ? AppColors.success : AppColors.error),
      ),
    );
  }
}
