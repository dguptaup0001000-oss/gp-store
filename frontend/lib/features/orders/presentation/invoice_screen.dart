import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import 'orders_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class InvoiceScreen extends ConsumerWidget {
  const InvoiceScreen({super.key, required this.orderId});

  final int orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final invoiceAsync = ref.watch(orderInvoiceProvider(orderId));

    return Scaffold(
      appBar: AppBar(title: const Text('Invoice')),
      body: invoiceAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load your invoice: ${extractErrorMessage(error)}"),
              TextButton(
                onPressed: hapticize(() => ref.invalidate(orderInvoiceProvider(orderId))),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
        data: (invoice) => ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text(invoice.invoiceNumber, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 4),
            if (invoice.orderNumber != null)
              Text('For order #${invoice.orderNumber}', style: Theme.of(context).textTheme.bodyMedium),
            if (invoice.invoiceDate != null)
              Text(_formatDate(invoice.invoiceDate!), style: Theme.of(context).textTheme.bodyMedium),
            const SizedBox(height: 24),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
              child: Column(
                children: [
                  if (invoice.subtotal != null) _row('Subtotal', invoice.subtotal!),
                  if (invoice.taxAmount != null) _row('GST (included in prices)', invoice.taxAmount!),
                  if (invoice.discountAmount != null && invoice.discountAmount! > 0)
                    _row('Discount', -invoice.discountAmount!),
                  if (invoice.deliveryCharge != null) _row('Delivery Charge', invoice.deliveryCharge!),
                  const Divider(height: 24),
                  _row('Grand Total', invoice.grandTotal, bold: true),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Text(
              'GST is included in the prices you paid, not added on top - this shows how much of your total was tax, '
              'for your own records.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }

  Widget _row(String label, double amount, {bool bold = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(fontWeight: bold ? FontWeight.w700 : FontWeight.w400)),
          Text('₹${amount.toStringAsFixed(2)}', style: TextStyle(fontWeight: bold ? FontWeight.w700 : FontWeight.w400)),
        ],
      ),
    );
  }

  String _formatDate(String iso) {
    try {
      final date = DateTime.parse(iso);
      return '${date.day}/${date.month}/${date.year}';
    } catch (_) {
      return iso;
    }
  }
}
