import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

/// Purely a review/signal list - the backend deliberately attaches no
/// auto-refund or auto-action to a breach (see the backend endpoint's own
/// comment). This screen just makes the list visible so an admin can decide
/// what to do about each one manually.
class AdminDeliveryBreachesScreen extends ConsumerWidget {
  const AdminDeliveryBreachesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final breachesAsync = ref.watch(adminDeliveryBreachesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Delivery Guarantee Breaches')),
      body: breachesAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load breach data: ${extractErrorMessage(error)}"),
              TextButton(onPressed: hapticize(() => ref.invalidate(adminDeliveryBreachesProvider)), child: const Text('Retry')),
            ],
          ),
        ),
        data: (breaches) {
          if (breaches.isEmpty) {
            return const Center(
              child: Text('No delivery guarantee breaches - great job!', style: TextStyle(color: AdminColors.textSecondary)),
            );
          }

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(adminDeliveryBreachesProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: breaches.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, index) {
                final breach = breaches[index];
                return Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: AdminColors.danger.withValues(alpha: 0.08),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        breach.orderNumber != null ? 'Order #${breach.orderNumber}' : 'Delivery #${breach.id}',
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(height: 4),
                      if (breach.estimatedDeliveryTime != null)
                        Text('Promised: ${breach.estimatedDeliveryTime}', style: Theme.of(context).textTheme.bodyMedium),
                      if (breach.deliveredAt != null)
                        Text('Actually delivered: ${breach.deliveredAt}', style: Theme.of(context).textTheme.bodyMedium)
                      else
                        Text('Status: ${breach.deliveryStatus ?? "unknown"}', style: Theme.of(context).textTheme.bodyMedium),
                      if (breach.deliveryPersonName != null)
                        Text('Partner: ${breach.deliveryPersonName}', style: Theme.of(context).textTheme.bodyMedium),
                    ],
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }
}
