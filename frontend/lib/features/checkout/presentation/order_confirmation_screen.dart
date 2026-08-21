import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/theme/app_theme.dart';

class OrderConfirmationScreen extends StatelessWidget {
  const OrderConfirmationScreen({
    super.key,
    required this.orderNumber,
    required this.paymentMethod,
    this.upiPaymentLink,
    this.verifiedPaymentStatus,
  });

  final String orderNumber;
  final String paymentMethod;
  final String? upiPaymentLink;

  /// The BACKEND's verdict for an online payment, or null for COD/UPI.
  ///
  /// Never the SDK's. This screen is reached only after the app has asked
  /// the server what actually happened, so a customer whose payment did not
  /// complete is told that here rather than seeing "Order confirmed" and
  /// discovering the truth in their order history later.
  final String? verifiedPaymentStatus;

  bool get _onlinePaymentIncomplete =>
      paymentMethod == 'ONLINE' && verifiedPaymentStatus != null && verifiedPaymentStatus != 'SUCCESS';

  Future<void> _openUpiApp(BuildContext context) async {
    if (upiPaymentLink == null) return;

    final uri = Uri.parse(upiPaymentLink!);
    final launched = await launchUrl(uri, mode: LaunchMode.externalApplication);

    if (!launched && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No UPI app found - install GPay, PhonePe, or Paytm to pay')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final isUpi = paymentMethod == 'UPI' && upiPaymentLink != null;

    // An online payment that did not complete gets a different headline and
    // a different instruction. The ORDER still exists and is still theirs -
    // it is simply unpaid, and retryable from order history - so this is not
    // an error screen either.
    if (_onlinePaymentIncomplete) {
      return _IncompletePaymentView(
        orderNumber: orderNumber,
        status: verifiedPaymentStatus!,
      );
    }

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.check_circle, color: AppColors.success, size: 72),
                const SizedBox(height: 16),
                Text('Order Placed!', style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 8),
                Text('Order #$orderNumber', style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: 24),

                if (isUpi) ...[
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: AppColors.cardBackground,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Column(
                      children: [
                        Text(
                          'Complete your payment to confirm the order',
                          textAlign: TextAlign.center,
                          style: TextStyle(fontWeight: FontWeight.w600),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    onPressed: () => _openUpiApp(context),
                    icon: const Icon(Icons.account_balance_wallet_outlined),
                    label: const Text('Pay with UPI App'),
                  ),
                ] else ...[
                  const Text(
                    "You'll pay in cash when your order arrives.",
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppColors.textSecondary),
                  ),
                ],

                const SizedBox(height: 24),
                TextButton(
                  onPressed: () { final router = GoRouter.of(context); Navigator.of(context).popUntil((r) => r.isFirst); router.go('/'); },
                  child: const Text('Continue Shopping'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Shown when the backend says an online payment did not complete.
///
/// Deliberately calm. Nothing has gone wrong with the ORDER - it exists,
/// it is reserved, and the customer can pay for it from their order
/// history. Treating this as a failure screen would suggest the basket was
/// lost, which is both untrue and the thing people fear at this moment.
class _IncompletePaymentView extends StatelessWidget {
  const _IncompletePaymentView({required this.orderNumber, required this.status});

  final String orderNumber;
  final String status;

  String get _explanation => switch (status) {
        'CANCELLED' => 'The payment was cancelled before it completed.',
        'EXPIRED' => 'The payment window closed before it completed.',
        'FAILED' => 'The payment did not go through.',
        // PENDING: the customer may genuinely still be paying in their bank
        // app, so this must not claim it failed.
        _ => 'We have not received confirmation of this payment yet.',
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(Icons.schedule_outlined, size: 64, color: AppColors.textSecondary),
                const SizedBox(height: 16),
                Text('Order $orderNumber is saved',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 10),
                Text(_explanation, textAlign: TextAlign.center),
                const SizedBox(height: 6),
                const Text(
                  'Your items are held. You can pay for this order from My Orders.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: AppColors.textSecondary),
                ),
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: () => Navigator.of(context).popUntil((route) => route.isFirst),
                  child: const Text('Back to shopping'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
