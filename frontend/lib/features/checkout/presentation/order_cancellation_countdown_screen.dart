import 'dart:async';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/theme/app_theme.dart';
import '../../cart/domain/cart_models.dart';
import '../domain/checkout_models.dart';

/// The 10-second pause between tapping "Place Order" and the order/payment
/// actually being created. Shows the full price breakdown and product list
/// so there's no ambiguity about what's about to be charged, plus a visibly
/// draining countdown bar and an explicit Cancel button.
///
/// Deliberately stateless w.r.t. the backend - nothing here calls the API.
/// Popping true means "the 10s elapsed, go ahead and place the order";
/// popping false (via Cancel or system back) means "abort, nothing happened
/// and nothing needs to be undone."
class OrderCancellationCountdownScreen extends StatefulWidget {
  const OrderCancellationCountdownScreen({
    super.key,
    required this.preview,
    required this.items,
  });

  final CheckoutPreview preview;
  final List<CartItemModel> items;

  @override
  State<OrderCancellationCountdownScreen> createState() =>
      _OrderCancellationCountdownScreenState();
}

class _OrderCancellationCountdownScreenState
    extends State<OrderCancellationCountdownScreen> {
  static const _totalSeconds = 5;
  int _secondsRemaining = _totalSeconds;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), _tick);
  }

  void _tick(Timer timer) {
    if (_secondsRemaining <= 1) {
      timer.cancel();
      if (mounted) Navigator.of(context).pop(true);
      return;
    }
    setState(() => _secondsRemaining--);
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  void _cancel() {
    HapticFeedback.lightImpact();
    _timer?.cancel();
    Navigator.of(context).pop(false);
  }

  @override
  Widget build(BuildContext context) {
    final preview = widget.preview;
    final progress = _secondsRemaining / _totalSeconds;
    final urgent = _secondsRemaining <= 10;

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) _cancel();
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Confirm Your Order'),
          automaticallyImplyLeading: false,
        ),
        body: SafeArea(
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                child: Column(
                  children: [
                    Text(
                      'Placing your order in $_secondsRemaining s',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w700,
                            color: urgent ? AppColors.error : null,
                          ),
                    ),
                    const SizedBox(height: 10),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: LinearProgressIndicator(
                        value: progress,
                        minHeight: 10,
                        backgroundColor: AppColors.cardBackground,
                        valueColor: AlwaysStoppedAnimation(
                          urgent ? AppColors.error : AppColors.primary,
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Review your order below. You can still cancel - nothing has been charged yet.',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  children: [
                    _sectionLabel(context, 'Items (${widget.items.length})'),
                    ...widget.items.map((item) => _ItemRow(item: item)),
                    const Divider(height: 28),
                    _sectionLabel(context, 'Order Summary'),
                    _summaryRow('Subtotal', preview.subtotal),
                    if (preview.discountAmount > 0)
                      _summaryRow('Discount', -preview.discountAmount),
                    _summaryRow(
                      'Delivery Fee',
                      preview.freeDeliveryApplied ? null : preview.deliveryFee,
                      freeLabel: preview.freeDeliveryApplied,
                    ),
                    const Divider(height: 20),
                    _summaryRow('Total', preview.estimatedTotal, bold: true),
                    const SizedBox(height: 16),
                  ],
                ),
              ),
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: SizedBox(
                    width: double.infinity,
                    child: OutlinedButton(
                      onPressed: _cancel,
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppColors.error,
                        side: const BorderSide(color: AppColors.error),
                        padding: const EdgeInsets.symmetric(vertical: 14),
                      ),
                      child: const Text('Cancel Order'),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _sectionLabel(BuildContext context, String text) => Padding(
        padding: const EdgeInsets.only(bottom: 8, top: 4),
        child: Text(text, style: Theme.of(context).textTheme.titleMedium),
      );

  Widget _summaryRow(String label, double? value, {bool bold = false, bool freeLabel = false}) {
    final text = freeLabel ? 'FREE' : '₹${(value ?? 0).toStringAsFixed(0)}';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(fontWeight: bold ? FontWeight.w700 : FontWeight.w400)),
          Text(text, style: TextStyle(fontWeight: bold ? FontWeight.w700 : FontWeight.w400)),
        ],
      ),
    );
  }
}

class _ItemRow extends StatelessWidget {
  const _ItemRow({required this.item});

  final CartItemModel item;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: item.imageUrl != null
                ? CachedNetworkImage(
                    imageUrl: item.imageUrl!,
                    width: 44,
                    height: 44,
                    // contain (not cover) - matches every other product
                    // image in the app, doesn't crop.
                    fit: BoxFit.contain,
                    // 44x44 tile - avoid decoding the full original.
                    memCacheWidth: 130,
                    errorWidget: (_, __, ___) => _placeholderIcon(),
                  )
                : _placeholderIcon(),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  item.productName ?? 'Item',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                if (item.productBrand != null && item.productBrand!.isNotEmpty)
                  Text(
                    item.productBrand!,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                Text(
                  '${item.quantity} x ₹${item.price.toStringAsFixed(0)}${item.unit != null ? ' / ${item.unit}' : ''}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
          Text(
            '₹${item.totalPrice.toStringAsFixed(0)}',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }

  Widget _placeholderIcon() => Container(
        width: 44,
        height: 44,
        color: AppColors.cardBackground,
        child: const Icon(Icons.shopping_bag_outlined, size: 20),
      );
}
