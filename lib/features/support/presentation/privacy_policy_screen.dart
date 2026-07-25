import 'package:flutter/material.dart';

import '../../../core/theme/app_theme.dart';

class PrivacyPolicyScreen extends StatelessWidget {
  const PrivacyPolicyScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Privacy Policy')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: AppColors.error.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(10)),
            child: const Text(
              'This is a draft reflecting what this app actually collects, written to give you a real starting point '
              '- not legal advice. Have it reviewed by a lawyer familiar with Indian data protection and e-commerce '
              'rules (including the Digital Personal Data Protection Act) before publishing this app.',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
            ),
          ),
          const SizedBox(height: 20),
          _section('Information We Collect', [
            'Name, mobile number, and optionally email, when you create an account.',
            'Your delivery address and GPS location, to check whether we can deliver to you and estimate delivery time.',
            'Your order history, including items purchased, amounts, and delivery status.',
            'Your chosen payment method (Cash on Delivery or UPI). We never see or store your card, bank, or UPI PIN details - UPI payments go directly between you and your own UPI app.',
            'Reviews and ratings you choose to submit.',
          ]),
          _section('How We Use Your Information', [
            'To fulfil and deliver your orders.',
            'To check delivery eligibility for your address.',
            'To send order status updates and notifications.',
            'To personalise product recommendations based on your own order history.',
            'To respond to support requests you send us.',
          ]),
          _section('What We Don\'t Do', [
            'We don\'t sell your personal information to third parties.',
            'We don\'t share your data with advertisers.',
          ]),
          _section('Your Choices', [
            'You can edit or delete your saved addresses at any time.',
            'You can delete your own reviews at any time.',
            'You can deactivate your account by contacting support.',
          ]),
          _section('Contact', [
            'Questions about this policy can be sent through the Contact Us screen in this app.',
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
