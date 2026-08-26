import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/config/app_environment.dart';
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
            decoration: BoxDecoration(
              color: AppColors.error.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Text(
              'This describes what this app actually collects. It is not legal '
              'advice. Have it reviewed by a lawyer familiar with Indian data '
              'protection and e-commerce rules before treating a store listing as final.',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
            ),
          ),
          const SizedBox(height: 12),
          TextButton(
            onPressed: () => launchUrl(
              Uri.parse(AppEnvironment.publicPrivacyPolicyUrl),
              mode: LaunchMode.externalApplication,
            ),
            child: const Text('Open the public privacy policy'),
          ),
          const SizedBox(height: 8),
          _section('Information We Collect', [
            'Name, mobile number, and optionally email, when you create an account.',
            'Your delivery address. If you use current location while adding an address, GPS is read once. We do not track you in the background.',
            'Your order history, including items purchased, amounts, and delivery status.',
            'Your chosen payment method (Cash on Delivery, UPI, or online checkout when the shop has configured it). We never see or store your card, bank, or UPI PIN details.',
            'Reviews and ratings you choose to submit.',
            'A push-notification token, if Firebase is configured, so we can send order updates.',
            'Optional voice search uses the phone\'s on-device recogniser. We do not record or upload the audio.',
          ]),
          _section('How We Use Your Information', [
            'To fulfil and deliver your orders inside our delivery areas.',
            'To send order status updates.',
            'To personalise product recommendations based on your own order history.',
            'To respond to support requests you send us.',
          ]),
          _section('What We Don\'t Do', [
            'We don\'t sell your personal information to third parties.',
            'We don\'t share your data with advertisers.',
            'We don\'t track delivery workers or customers in the background. Worker GPS runs only while a delivery screen is open.',
          ]),
          _section('Your Choices', [
            'You can edit or delete your saved addresses at any time.',
            'You can delete your own reviews at any time.',
            'You can delete your account in this app. Deletion requires your current password so a stolen login session is not enough.',
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
