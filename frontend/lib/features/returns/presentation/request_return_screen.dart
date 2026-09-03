import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/images/gp_network_image.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../orders/domain/order_models.dart';
import 'returns_providers.dart';

/// Choosing what to send back.
///
/// HOW MANY CAN GO BACK IS ASKED OF THE SERVER, not worked out from the
/// order. The customer may have returned some of this order from another
/// phone, or last week; a form built from the order alone would offer three
/// when one is left, and the refusal would only arrive after they had filled
/// it in and pressed send.
///
/// NO AMOUNT IS SHOWN AS A PROMISE. The running total here is what these
/// lines were charged, which is what the shop will refund - but the figure
/// that actually moves is computed server-side at approval, and the copy
/// says "approximately" rather than quoting a number back as a commitment
/// the shop has not made yet.
class RequestReturnScreen extends ConsumerStatefulWidget {
  const RequestReturnScreen({super.key, required this.order});

  final OrderDetail order;

  @override
  ConsumerState<RequestReturnScreen> createState() => _RequestReturnScreenState();
}

class _RequestReturnScreenState extends ConsumerState<RequestReturnScreen> {
  final _reasonController = TextEditingController();

  /// Order-line id to how many of it the customer is sending back.
  final Map<int, int> _chosen = {};

  bool _isSending = false;

  @override
  void dispose() {
    _reasonController.dispose();
    super.dispose();
  }

  double get _approximateRefund {
    double total = 0;
    for (final item in widget.order.items) {
      final id = item.orderItemId;
      if (id == null) continue;
      final count = _chosen[id] ?? 0;
      if (count == 0) continue;
      // The line's own unit price, the same figure the backend will use.
      final unit = item.quantity == 0 ? 0.0 : item.totalPrice / item.quantity;
      total += unit * count;
    }
    return total;
  }

  Future<void> _send() async {
    if (_chosen.isEmpty) return;
    setState(() => _isSending = true);
    try {
      await ref.read(returnsRepositoryProvider).request(
            orderId: widget.order.orderId,
            lines: _chosen,
            reason: _reasonController.text,
          );
      ref.invalidate(myReturnsProvider);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isSending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final returnable = ref.watch(returnableLinesProvider(widget.order.orderId));

    return Scaffold(
      appBar: AppBar(title: const Text('Return items')),
      body: returnable.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => Padding(
          padding: const EdgeInsets.all(24),
          child: Center(
            child: Text(
              "Couldn't check what can be returned: ${extractErrorMessage(error)}",
              textAlign: TextAlign.center,
            ),
          ),
        ),
        data: (limits) {
          final lines = widget.order.items
              .where((item) =>
                  item.orderItemId != null && (limits[item.orderItemId] ?? 0) > 0)
              .toList();

          if (lines.isEmpty) {
            return const Padding(
              padding: EdgeInsets.all(24),
              child: Center(
                child: Text(
                  'Everything on this order has already been returned, or the '
                  'return window has closed.',
                  textAlign: TextAlign.center,
                ),
              ),
            );
          }

          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              for (final item in lines)
                _LinePicker(
                  item: item,
                  maximum: limits[item.orderItemId] ?? 0,
                  chosen: _chosen[item.orderItemId] ?? 0,
                  onChanged: (value) => setState(() {
                    if (value <= 0) {
                      _chosen.remove(item.orderItemId);
                    } else {
                      _chosen[item.orderItemId!] = value;
                    }
                  }),
                ),
              const SizedBox(height: 16),
              TextField(
                controller: _reasonController,
                maxLines: 3,
                maxLength: 500,
                decoration: const InputDecoration(
                  labelText: 'Why are you sending it back?',
                  hintText: 'e.g. the atta packet was damp when it arrived',
                  alignLabelWithHint: true,
                ),
              ),
              const SizedBox(height: 8),
              if (_chosen.isNotEmpty)
                Text(
                  'Approximately ₹${_approximateRefund.toStringAsFixed(0)} '
                  'would come back once the shop has the items. '
                  'They will check them first.',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _chosen.isEmpty || _isSending ? null : _send,
                child: _isSending
                    ? const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('Ask to return these'),
              ),
              const SizedBox(height: 24),
            ],
          );
        },
      ),
    );
  }
}

class _LinePicker extends StatelessWidget {
  const _LinePicker({
    required this.item,
    required this.maximum,
    required this.chosen,
    required this.onChanged,
  });

  final OrderItemDetail item;
  final int maximum;
  final int chosen;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          SizedBox(
            width: 44,
            height: 44,
            child: GpNetworkImage(
                url: item.imageUrl, renderWidth: 44, fit: BoxFit.cover),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.productName ?? 'Item',
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                Text(
                  maximum == item.quantity
                      ? '${item.quantity} ordered'
                      : '$maximum of ${item.quantity} can still go back',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
          IconButton(
            onPressed: chosen == 0 ? null : hapticize(() => onChanged(chosen - 1)),
            icon: const Icon(Icons.remove_circle_outline),
          ),
          SizedBox(
            width: 24,
            child: Text('$chosen', textAlign: TextAlign.center),
          ),
          IconButton(
            // Capped at what the server said is left, so the form cannot ask
            // for something that will be refused.
            onPressed:
                chosen >= maximum ? null : hapticize(() => onChanged(chosen + 1)),
            icon: const Icon(Icons.add_circle_outline),
          ),
        ],
      ),
    );
  }
}
