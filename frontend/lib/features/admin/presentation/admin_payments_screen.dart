import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_format.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/admin_payment_model.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

/// Money in and money back out.
///
/// The actions, the pagination and the provider invalidation are unchanged.
/// What changed is that the screen now tells the truth about status - every
/// payment used to render its stage in the same brand green, so a refund the
/// shop still owed a customer looked exactly like one already settled - and
/// that the two irreversible actions ask before they fire.
class AdminPaymentsScreen extends ConsumerWidget {
  const AdminPaymentsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final paymentsAsync = ref.watch(adminAllPaymentsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Payments')),
      body: paymentsAsync.when(
        loading: () => const _PaymentsSkeleton(),
        error: (error, stackTrace) => AdminErrorState(
          // Shows the real failure reason rather than one static string.
          message: "Couldn't load payments: ${extractErrorMessage(error)}",
          onRetry: hapticize(() => ref.invalidate(adminAllPaymentsProvider)),
        ),
        data: (page) {
          final payments = page.payments;
          if (payments.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.payments_outlined,
              title: 'No payments yet',
              message: 'Payments appear here as soon as the shop takes an order.',
            );
          }

          final hasMore = ref.read(adminAllPaymentsProvider.notifier).hasMore;

          return RefreshIndicator(
            color: AdminColors.primary,
            onRefresh: () async => ref.invalidate(adminAllPaymentsProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(AdminSpacing.lg),
              itemCount: payments.length + (hasMore ? 1 : 0),
              separatorBuilder: (_, __) => const SizedBox(height: AdminSpacing.sm),
              itemBuilder: (context, index) {
                if (index == payments.length) {
                  WidgetsBinding.instance.addPostFrameCallback(
                    (_) => ref.read(adminAllPaymentsProvider.notifier).loadMore(),
                  );
                  return const Padding(
                    padding: EdgeInsets.all(AdminSpacing.lg),
                    child: Center(
                      child: SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    ),
                  );
                }
                return _PaymentTile(payment: payments[index]);
              },
            ),
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
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _isProcessing = false);
    }
  }

  /// Every action on this screen moves money and none of them can be undone
  /// from the app. A mis-tap used to start a refund immediately.
  Future<bool> _confirm(_PaymentAction action) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(action.confirmTitle),
        content: Text(action.confirmMessage),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(action.confirmCta),
          ),
        ],
      ),
    );
    return confirmed ?? false;
  }

  @override
  Widget build(BuildContext context) {
    final payment = widget.payment;
    final orderId = payment.orderId;
    final actions = _actionsFor(payment.paymentStatus);

    return Container(
      padding: const EdgeInsets.all(AdminSpacing.lg),
      decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  payment.orderNumber != null
                      ? '#${payment.orderNumber}'
                      : 'Payment #${payment.id}',
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    color: AdminColors.textPrimary,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              const SizedBox(width: AdminSpacing.sm),
              Text(AdminFormat.rupees(payment.amount), style: AdminText.numeric),
            ],
          ),
          if (payment.customerName != null) ...[
            const SizedBox(height: 2),
            Text(
              payment.customerName!,
              style: AdminText.caption,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
          const SizedBox(height: AdminSpacing.sm),
          Wrap(
            spacing: AdminSpacing.sm,
            runSpacing: AdminSpacing.xs,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              AdminStatusBadge(
                label: AdminStatusBadge.humanizeStatus(payment.paymentStatus),
                tone: AdminStatusBadge.toneForPaymentStatus(
                    payment.paymentStatus),
                dense: true,
              ),
              if (payment.paymentMethod != null &&
                  payment.paymentMethod!.isNotEmpty)
                Text(payment.paymentMethod!, style: AdminText.caption),
            ],
          ),
          if (payment.transactionId != null) ...[
            const SizedBox(height: AdminSpacing.xs),
            Text('Txn: ${payment.transactionId}', style: AdminText.caption),
          ],
          if (orderId != null && actions.isNotEmpty) ...[
            const SizedBox(height: AdminSpacing.md),
            if (_isProcessing)
              const LinearProgressIndicator()
            else
              Wrap(
                spacing: AdminSpacing.sm,
                runSpacing: AdminSpacing.sm,
                children: actions
                    .map((action) => OutlinedButton(
                          style: OutlinedButton.styleFrom(
                            // Overrides the theme's full-width minimum: these
                            // sit side by side inside a card, not stacked as
                            // page-level actions.
                            minimumSize: Size.zero,
                            padding: const EdgeInsets.symmetric(
                              horizontal: AdminSpacing.lg,
                              vertical: AdminSpacing.sm,
                            ),
                            foregroundColor: action.destructive
                                ? AdminColors.danger
                                : AdminColors.primaryDark,
                            side: BorderSide(
                              color: action.destructive
                                  ? AdminColors.danger
                                  : AdminColors.borderStrong,
                            ),
                          ),
                          onPressed: hapticize(() async {
                            if (!await _confirm(action)) return;
                            await _run(() => action.onRun(ref, orderId));
                          }),
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
        return [
          _PaymentAction(
            label: 'Confirm UPI Received',
            confirmTitle: 'Confirm payment received?',
            confirmMessage:
                'Only do this once the money is actually in the shop account. '
                'Confirming marks the order paid and cannot be undone here.',
            confirmCta: 'Confirm received',
            onRun: (ref, orderId) => ref
                .read(adminProductsRepositoryProvider)
                .confirmUpiPayment(orderId),
          )
        ];
      case 'SUCCESS':
      case 'COD_RECEIVED':
        return [
          _PaymentAction(
            label: 'Start Refund',
            destructive: true,
            confirmTitle: 'Start a refund?',
            confirmMessage:
                'This begins returning the money to the customer. It cannot '
                'be cancelled from the app once started.',
            confirmCta: 'Start refund',
            onRun: (ref, orderId) =>
                ref.read(adminProductsRepositoryProvider).refundPayment(orderId),
          )
        ];
      case 'REFUND_PENDING':
        return [
          _PaymentAction(
            label: 'Mark Refund Complete',
            confirmTitle: 'Mark this refund complete?',
            confirmMessage:
                'Only do this once the customer has actually received the '
                'money. This closes the refund.',
            confirmCta: 'Mark complete',
            onRun: (ref, orderId) => ref
                .read(adminProductsRepositoryProvider)
                .completeRefund(orderId),
          )
        ];
      default:
        return [];
    }
  }
}

class _PaymentAction {
  const _PaymentAction({
    required this.label,
    required this.confirmTitle,
    required this.confirmMessage,
    required this.confirmCta,
    required this.onRun,
    this.destructive = false,
  });

  final String label;
  final String confirmTitle;
  final String confirmMessage;
  final String confirmCta;
  final bool destructive;
  final Future<void> Function(WidgetRef ref, int orderId) onRun;
}

class _PaymentsSkeleton extends StatelessWidget {
  const _PaymentsSkeleton();

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.all(AdminSpacing.lg),
      itemCount: 6,
      separatorBuilder: (_, __) => const SizedBox(height: AdminSpacing.sm),
      itemBuilder: (_, __) => Container(
        padding: const EdgeInsets.all(AdminSpacing.lg),
        decoration: BoxDecoration(
          color: AdminColors.surface,
          borderRadius: AdminRadius.card,
          border: Border.all(color: AdminColors.border),
        ),
        child: const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AdminSkeleton(height: 14, width: 130),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 10, width: 90),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 16, width: 80),
          ],
        ),
      ),
    );
  }
}
