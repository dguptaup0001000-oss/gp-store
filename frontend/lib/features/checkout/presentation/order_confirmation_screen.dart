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
  });

  final String orderNumber;
  final String paymentMethod;
  final String? upiPaymentLink;

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
