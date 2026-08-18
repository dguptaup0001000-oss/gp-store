import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/delivery_partner_models.dart';
import 'admin_delivery_partner_form_dialog.dart';
import 'admin_providers.dart';

class AdminDeliveryPartnersScreen extends ConsumerWidget {
  const AdminDeliveryPartnersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final partnersAsync = ref.watch(adminDeliveryPartnersProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Delivery Partners')),
      body: partnersAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load delivery partners: ${extractErrorMessage(error)}"),
              TextButton(onPressed: () => ref.invalidate(adminDeliveryPartnersProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (partners) {
          if (partners.isEmpty) {
            return const Center(
              child: Text('No delivery partners yet - tap + to add one', style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: partners.length,
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, index) => _PartnerTile(partner: partners[index]),
          );
        },
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          final saved = await showDialog<bool>(
            context: context,
            builder: (context) => const AdminDeliveryPartnerFormDialog(),
          );
          if (saved == true) ref.invalidate(adminDeliveryPartnersProvider);
        },
        icon: const Icon(Icons.add),
        label: const Text('Add Partner'),
      ),
    );
  }
}

class _PartnerTile extends ConsumerWidget {
  const _PartnerTile({required this.partner});

  final DeliveryPartnerModel partner;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () async {
        final saved = await showDialog<bool>(
          context: context,
          builder: (context) => AdminDeliveryPartnerFormDialog(partner: partner),
        );
        if (saved == true) ref.invalidate(adminDeliveryPartnersProvider);
      },
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
        child: Row(
          children: [
            CircleAvatar(
              backgroundColor: partner.available ? AppColors.success : AppColors.textSecondary,
              child: Icon(
                partner.vehicleType.toUpperCase() == 'PICKUP' ? Icons.local_shipping_outlined : Icons.two_wheeler_outlined,
                color: Colors.white,
                size: 20,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(partner.name, style: const TextStyle(fontWeight: FontWeight.w700)),
                  Text('${partner.mobile} · ${partner.vehicleType}${partner.vehicleNumber != null ? ' (${partner.vehicleNumber})' : ''}',
                      style: Theme.of(context).textTheme.bodyMedium),
                  Text(
                    partner.available ? 'Available' : 'Not available',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: partner.available ? AppColors.success : AppColors.textSecondary,
                    ),
                  ),
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
