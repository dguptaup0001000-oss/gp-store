import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/admin_payment_model.dart';
import 'admin_providers.dart';

class AdminPaymentsScreen extends ConsumerWidget {
  const AdminPaymentsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final paymentsAsync = ref.watch(adminAllPaymentsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Payments')),
      body: paymentsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(extractErrorMessage(error), textAlign: TextAlign.center),
              TextButton(onPressed: () => ref.invalidate(adminAllPaymentsProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (payments) {
          if (payments.isEmpty) {
            return const Center(
              child: Text('No payments yet', style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: payments.length,
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, index) => _PaymentTile(payment: payments[index]),
          );
        },
      ),
    );
  }
}

class _PaymentTile extends ConsumerStatefulWidget {
  const _PaymentTile({required this.payment});

  final AdminPayment payment;

  @override
  ConsumerState<_PaymentTile> createState() => _PaymentTileState();
}

class _PaymentTileState extends ConsumerState<_PaymentTile> {
  bool _isProcessing = false;

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _isProcessing = true);
    try {
      await action();
      ref.invalidate(adminAllPaymentsProvider);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _isProcessing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final payment = widget.payment;
    final orderId = payment.orderId;

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                payment.orderNumber != null ? '#${payment.orderNumber}' : 'Payment #${payment.id}',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              Text('₹${payment.amount.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w700)),
            ],
          ),
          if (payment.customerName != null) Text(payment.customerName!, style: Theme.of(context).textTheme.bodyMedium),
          const SizedBox(height: 4),
          Row(
            children: [
              Text(payment.paymentMethod ?? '', style: const TextStyle(fontSize: 11, color: AppColors.textSecondary)),
              const SizedBox(width: 8),
              Text(
                payment.paymentStatus?.replaceAll('_', ' ') ?? '',
                style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.primary),
              ),
            ],
          ),
          if (payment.transactionId != null) ...[
            const SizedBox(height: 4),
            Text('Txn: ${payment.transactionId}', style: const TextStyle(fontSize: 11, color: AppColors.textSecondary)),
          ],
          if (orderId != null && _actionsFor(payment.paymentStatus).isNotEmpty) ...[
            const SizedBox(height: 10),
            if (_isProcessing)
              const LinearProgressIndicator()
            else
              Wrap(
                spacing: 8,
                children: _actionsFor(payment.paymentStatus)
                    .map((action) => OutlinedButton(
                          onPressed: () => _run(() => action.onRun(ref, orderId)),
                          child: Text(action.label),
                        ))
                    .toList(),
              ),
          ],
        ],
      ),
    );
  }

  List<_PaymentAction> _actionsFor(String? status) {
    switch (status) {
      case 'PENDING':
        return [_PaymentAction('Confirm UPI Received', (ref, orderId) => ref.read(adminProductsRepositoryProvider).confirmUpiPayment(orderId))];
      case 'SUCCESS':
      case 'COD_RECEIVED':
        return [_PaymentAction('Start Refund', (ref, orderId) => ref.read(adminProductsRepositoryProvider).refundPayment(orderId))];
      case 'REFUND_PENDING':
        return [_PaymentAction('Mark Refund Complete', (ref, orderId) => ref.read(adminProductsRepositoryProvider).completeRefund(orderId))];
      default:
        return [];
    }
  }
}

class _PaymentAction {
  const _PaymentAction(this.label, this.onRun);
  final String label;
  final Future<void> Function(WidgetRef ref, int orderId) onRun;
}
