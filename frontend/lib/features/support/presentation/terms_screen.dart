import 'package:flutter/material.dart';

import '../../../core/theme/app_theme.dart';

class TermsScreen extends StatelessWidget {
  const TermsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Terms of Service')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: AppColors.error.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(10)),
            child: const Text(
              'This is a draft reflecting how this app actually works, written to give you a real starting point '
              '- not legal advice. Have it reviewed by a lawyer before publishing this app, especially around '
              'Indian consumer protection e-commerce rules.',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
            ),
          ),
          const SizedBox(height: 20),
          _section('Orders & Delivery', [
            'Delivery is only available within our serviceable radius of the store, checked automatically at checkout.',
            'Estimated delivery times are based on distance and are estimates, not guarantees.',
            'Orders can be cancelled from the app while still in progress; delivered orders cannot be cancelled.',
          ]),
          _section('Payments', [
            'We accept Cash on Delivery and UPI. UPI payments are made directly to our UPI ID through your own UPI app - we do not process card payments.',
            'For UPI payments, confirmation may take a short time to reflect after you pay.',
          ]),
          _section('Pricing', [
            'Prices, discounts, and delivery fees shown at checkout are final at the time of order placement.',
            'Coupon codes are subject to their own stated conditions (minimum order, expiry, usage limits).',
          ]),
          _section('Reviews', [
            'You may only review products you have actually purchased.',
            'Reviews should reflect genuine experiences. We reserve the right to remove reviews that violate this.',
          ]),
          _section('Account', [
            'You are responsible for keeping your login credentials secure.',
            'We may deactivate accounts involved in fraud or abuse.',
          ]),
        ],
      ),
    );
  }

  Widget _section(String title, List<String> points) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
          const SizedBox(height: 8),
          ...points.map((p) => Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Text('•  $p', style: const TextStyle(fontSize: 13, height: 1.4)),
              )),
        ],
      ),
    );
  }
}
