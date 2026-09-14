import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/order_group_models.dart';
import '../domain/payment_status.dart';
import 'order_detail_screen.dart';
import 'order_group_providers.dart';

/// One press of Place Order, and everything it became.
///
/// WHY THIS SCREEN EXISTS AT ALL. A basket spanning two kiranas is two
/// orders (§16) and the customer pressed one button. Their history showed two
/// unrelated rows with two numbers and no explanation - which reads as having
/// been charged twice. This is the missing middle: the checkout they remember
/// making, with each shop's half under it.
///
/// CANCELLING ANSWERS PER SHOP AND IS RENDERED THAT WAY. One kirana may still
/// be packing while the other's rider is at the door, so "cancel my order"
/// genuinely half-succeeds. Collapsing that into one verdict would tell the
/// customer something untrue about their money.
class OrderGroupScreen extends ConsumerWidget {
  const OrderGroupScreen({super.key, required this.groupId});

  final int groupId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final groupAsync = ref.watch(orderGroupProvider(groupId));

    return Scaffold(
      appBar: AppBar(title: const Text('Your checkout')),
      body: groupAsync.when(
        loading: () =>
            const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, _) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text("Couldn't load this checkout: ${extractErrorMessage(error)}"),
              const SizedBox(height: 8),
              TextButton(
                onPressed:
                    hapticize(() => ref.invalidate(orderGroupProvider(groupId))),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
        data: (group) => _GroupBody(group: group),
      ),
    );
  }
}

class _GroupBody extends ConsumerWidget {
  const _GroupBody({required this.group});

  final OrderGroupSummary group;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final anyCancellable = group.shopOrders.any((order) => order.cancellable);

    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(orderGroupProvider(group.id)),
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _Header(group: group),
          const SizedBox(height: 16),
          for (final shopOrder in group.shopOrders) ...[
            _ShopOrderCard(shopOrder: shopOrder),
            const SizedBox(height: 12),
          ],
          if (anyCancellable) ...[
            const SizedBox(height: 8),
            _CancelWholeCheckout(group: group),
          ],
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.group});

  final OrderGroupSummary group;

  @override
  Widget build(BuildContext context) {
    final shops = group.shopCount;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('#${group.groupNumber ?? group.id}',
              style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Text(
            shops == 1
                ? 'One shop'
                : '$shops shops · $shops separate orders, delivered separately',
            style: const TextStyle(
                fontSize: 12.5, color: AppColors.textSecondary),
          ),
          if (group.totalAmount != null) ...[
            const SizedBox(height: 8),
            Text('₹${group.totalAmount!.toStringAsFixed(0)}',
                style: const TextStyle(fontWeight: FontWeight.w700)),
          ],
        ],
      ),
    );
  }
}

class _ShopOrderCard extends StatelessWidget {
  const _ShopOrderCard({required this.shopOrder});

  final ShopOrderView shopOrder;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: hapticize(() => Navigator.of(context).push(
            MaterialPageRoute(
                builder: (_) => OrderDetailScreen(orderId: shopOrder.orderId)),
          )),
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
              children: [
                const Icon(Icons.storefront_outlined,
                    size: 16, color: AppColors.primary),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    shopOrder.shopName ??
                        (shopOrder.shopId == null
                            ? 'This shop'
                            : 'Shop ${shopOrder.shopId}'),
                    style: const TextStyle(fontWeight: FontWeight.w700),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (shopOrder.shopStatus != null)
                  _StatusPill(status: shopOrder.shopStatus!),
              ],
            ),
            const SizedBox(height: 8),
            Text('#${shopOrder.orderNumber ?? shopOrder.orderId}',
                style: const TextStyle(
                    fontSize: 12.5, color: AppColors.textSecondary)),
            const SizedBox(height: 8),
            if (shopOrder.totalAmount != null)
              Text('₹${shopOrder.totalAmount!.toStringAsFixed(0)}',
                  style: const TextStyle(fontWeight: FontWeight.w700)),
            if (shopOrder.deliveryFee != null && shopOrder.deliveryFee! > 0) ...[
              const SizedBox(height: 4),
              Text(
                'Includes ₹${shopOrder.deliveryFee!.toStringAsFixed(0)} delivery',
                style: const TextStyle(
                    fontSize: 12, color: AppColors.textSecondary),
              ),
            ],
            if (shopOrder.paymentStatus != null) ...[
              const SizedBox(height: 6),
              Text(
                'Payment: ${PaymentStatusInfo.label(shopOrder.paymentStatus!)}',
                style: const TextStyle(fontSize: 12),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: AppColors.secondary.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        status.replaceAll('_', ' '),
        style: const TextStyle(
          fontSize: 10.5,
          fontWeight: FontWeight.w700,
          color: AppColors.secondary,
        ),
      ),
    );
  }
}

/// Asks every shop in this checkout to cancel, and reports what each said.
class _CancelWholeCheckout extends ConsumerStatefulWidget {
  const _CancelWholeCheckout({required this.group});

  final OrderGroupSummary group;

  @override
  ConsumerState<_CancelWholeCheckout> createState() =>
      _CancelWholeCheckoutState();
}

class _CancelWholeCheckoutState extends ConsumerState<_CancelWholeCheckout> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton(
      onPressed: _busy ? null : hapticize(_confirmThenCancel, feedback: AppHapticFeedback.heavy),
      child: Text(_busy ? 'Cancelling…' : 'Cancel this checkout'),
    );
  }

  Future<void> _confirmThenCancel() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Cancel this checkout?'),
        content: Text(
          widget.group.shopCount > 1
              ? 'Each shop is asked separately. One may already be on its way '
                  'and refuse - you will be told which.'
              : 'The shop will be asked to cancel this order.',
        ),
        actions: [
          TextButton(
            onPressed: hapticize(() => Navigator.of(dialogContext).pop(false)),
            child: const Text('Keep it'),
          ),
          FilledButton(
            onPressed: hapticize(() => Navigator.of(dialogContext).pop(true),
                feedback: AppHapticFeedback.heavy),
            child: const Text('Cancel it'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _busy = true);
    try {
      final result = await ref
          .read(orderGroupRepositoryProvider)
          .cancelWholeCheckout(widget.group.id);
      ref.invalidate(orderGroupProvider(widget.group.id));
      ref.invalidate(myCheckoutsProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_outcomeMessage(result))),
      );
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(error))),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// PARTIAL SUCCESS IS SAID OUT LOUD. "Cancelled" over a checkout where one
  /// shop refused is the single most expensive thing this screen could get
  /// wrong: the customer stops expecting a delivery that is still coming.
  static String _outcomeMessage(OrderGroupCancelResult result) {
    if (result.allCancelled) return 'Cancelled.';
    if (!result.partiallyCancelled) {
      final reason = result.refused.first.reason;
      return reason ?? 'This could not be cancelled.';
    }
    final refused = result.refused.length;
    return 'Cancelled, except $refused order${refused == 1 ? '' : 's'} '
        'already too far along.';
  }
}
