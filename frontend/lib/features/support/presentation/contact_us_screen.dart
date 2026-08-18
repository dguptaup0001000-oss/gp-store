import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import 'support_providers.dart';

class ContactUsScreen extends ConsumerWidget {
  const ContactUsScreen({super.key});

  Future<void> _call(String phone) async {
    await launchUrl(Uri(scheme: 'tel', path: phone));
  }

  Future<void> _whatsapp(String phone) async {
    // wa.me needs digits only, no + or spaces.
    final digitsOnly = phone.replaceAll(RegExp(r'[^\d]'), '');
    await launchUrl(Uri.parse('https://wa.me/$digitsOnly'), mode: LaunchMode.externalApplication);
  }

  Future<void> _email(String email) async {
    await launchUrl(Uri(scheme: 'mailto', path: email));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storeInfoAsync = ref.watch(storeInfoProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Contact Us')),
      body: storeInfoAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load contact info: ${extractErrorMessage(error)}"),
              TextButton(onPressed: () => ref.invalidate(storeInfoProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (info) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _ContactTile(
              icon: Icons.call_outlined,
              label: 'Call us',
              value: info.supportPhone,
              onTap: () => _call(info.supportPhone),
            ),
            const SizedBox(height: 12),
            _ContactTile(
              icon: Icons.chat_outlined,
              label: 'WhatsApp',
              value: info.supportWhatsapp,
              onTap: () => _whatsapp(info.supportWhatsapp),
            ),
            const SizedBox(height: 12),
            _ContactTile(
              icon: Icons.email_outlined,
              label: 'Email',
              value: info.supportEmail,
              onTap: () => _email(info.supportEmail),
            ),
          ],
        ),
      ),
    );
  }
}

class _ContactTile extends StatelessWidget {
  const _ContactTile({required this.icon, required this.label, required this.value, required this.onTap});

  final IconData icon;
  final String label;
  final String value;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
        child: Row(
          children: [
            Icon(icon, color: AppColors.primary),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
                  Text(value, style: Theme.of(context).textTheme.bodyMedium),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
