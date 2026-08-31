import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/delivery_partner_models.dart';
import 'admin_delivery_partner_form_dialog.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminDeliveryPartnersScreen extends ConsumerWidget {
  const AdminDeliveryPartnersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final partnersAsync = ref.watch(adminDeliveryPartnersProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Delivery Partners')),
      body: partnersAsync.when(
        loading: () => const AdminListSkeleton(),
        error: (error, stackTrace) => AdminErrorState(
          // Shows the real failure reason rather than one static
          // string - an admin who can read the cause can act on it.
          message: "Couldn't load delivery partners: ${extractErrorMessage(error)}",
          onRetry: hapticize(() => ref.invalidate(adminDeliveryPartnersProvider)),
        ),
        data: (partners) {
          if (partners.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.delivery_dining_outlined,
              title: 'No delivery partners yet',
              message: 'Tap the + button to add your first rider.',
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
        onPressed: hapticize(() async {
          final saved = await showDialog<bool>(
            context: context,
            builder: (context) => const AdminDeliveryPartnerFormDialog(),
          );
          if (saved == true) ref.invalidate(adminDeliveryPartnersProvider);
        }),
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
      onTap: hapticize(() async {
        final saved = await showDialog<bool>(
          context: context,
          builder: (context) => AdminDeliveryPartnerFormDialog(partner: partner),
        );
        if (saved == true) ref.invalidate(adminDeliveryPartnersProvider);
      }),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: AdminColors.surface, borderRadius: BorderRadius.circular(12)),
        child: Row(
          children: [
            CircleAvatar(
              backgroundColor: partner.available ? AdminColors.success : AdminColors.textSecondary,
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
                      color: partner.available ? AdminColors.success : AdminColors.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AdminColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
