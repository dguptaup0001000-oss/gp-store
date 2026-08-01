import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/delivery_assignment_model.dart';
import 'delivery_partner_providers.dart';

class DeliveryDashboardScreen extends ConsumerWidget {
  const DeliveryDashboardScreen({super.key});

  Future<void> _toggleAvailability(WidgetRef ref, BuildContext context, bool newValue) async {
    try {
      await ref.read(deliveryPartnerRepositoryProvider).setMyAvailability(newValue);
      ref.invalidate(myDeliveryProfileProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final assignmentsAsync = ref.watch(myAssignmentsProvider);
    final profileAsync = ref.watch(myDeliveryProfileProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('My Deliveries'),
        actions: [
          profileAsync.when(
            loading: () => const SizedBox.shrink(),
            error: (error, stackTrace) => const SizedBox.shrink(),
            data: (profile) => Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: Row(
                children: [
                  Text(profile.available ? 'On duty' : 'Off duty', style: const TextStyle(fontSize: 12)),
                  Switch(
                    value: profile.available,
                    onChanged: (value) => _toggleAvailability(ref, context, value),
                  ),
                ],
              ),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Log out',
            onPressed: () => ref.read(authControllerProvider.notifier).logout(),
          ),
        ],
      ),
      body: assignmentsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text("Couldn't load your deliveries - check your connection"),
              TextButton(onPressed: () => ref.invalidate(myAssignmentsProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (assignments) {
          if (assignments.isEmpty) {
            return const Center(
              child: Text('No deliveries assigned right now', style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(myAssignmentsProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: assignments.length,
              separatorBuilder: (_, __) => const SizedBox(height: 12),
              itemBuilder: (context, index) => _AssignmentCard(assignment: assignments[index]),
            ),
          );
        },
      ),
    );
  }
}

class _AssignmentCard extends ConsumerStatefulWidget {
  const _AssignmentCard({required this.assignment});

  final DeliveryAssignment assignment;

  @override
  ConsumerState<_AssignmentCard> createState() => _AssignmentCardState();
}

class _AssignmentCardState extends ConsumerState<_AssignmentCard> {
  bool _isUpdating = false;

  Future<void> _callCustomer() async {
    final phone = widget.assignment.customerPhone;
    if (phone == null) return;
    await launchUrl(Uri(scheme: 'tel', path: phone));
  }

  Future<void> _navigate() async {
    final lat = widget.assignment.latitude;
    final lng = widget.assignment.longitude;
    if (lat == null || lng == null) return;
    // Universal Google Maps link - opens the Maps app if installed (mobile),
    // or the Maps website otherwise (including on web builds of this app) -
    // more reliable across platforms than the Android-only geo: URI scheme.
    await launchUrl(
      Uri.parse('https://www.google.com/maps/dir/?api=1&destination=$lat,$lng'),
      mode: LaunchMode.externalApplication,
    );
  }

  Future<void> _advanceStatus(String newStatus) async {
    setState(() => _isUpdating = true);

    try {
      await ref.read(deliveryPartnerRepositoryProvider).updateStatus(
            deliveryId: widget.assignment.deliveryId,
            status: newStatus,
          );
      ref.invalidate(myAssignmentsProvider);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isUpdating = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final assignment = widget.assignment;
    final status = assignment.deliveryStatus ?? 'ASSIGNED';

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('#${assignment.orderNumber ?? assignment.deliveryId}', style: const TextStyle(fontWeight: FontWeight.w700)),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(color: AppColors.primary.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(6)),
                child: Text(
                  status.replaceAll('_', ' '),
                  style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: AppColors.primary),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (assignment.customerName != null)
            Text(assignment.customerName!, style: const TextStyle(fontWeight: FontWeight.w600)),
          if (assignment.deliveryAddress != null)
            Text(assignment.deliveryAddress!, style: Theme.of(context).textTheme.bodyMedium),
          const SizedBox(height: 12),
          Row(
            children: [
              if (assignment.latitude != null && assignment.longitude != null)
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _navigate,
                    icon: const Icon(Icons.directions, size: 16),
                    label: const Text('Navigate'),
                  ),
                ),
              if (assignment.latitude != null && assignment.longitude != null && assignment.customerPhone != null)
                const SizedBox(width: 8),
              if (assignment.customerPhone != null)
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _callCustomer,
                    icon: const Icon(Icons.call, size: 16),
                    label: const Text('Call'),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          SizedBox(width: double.infinity, child: _statusActionButton(status)),
        ],
      ),
    );
  }

  Widget _statusActionButton(String status) {
    // Matches exactly the two transitions the backend gives real side
    // effects to (order status sync, notifications) - see
    // DeliveryService.updateDeliveryStatus.
    if (status == 'ASSIGNED') {
      return FilledButton(
        onPressed: _isUpdating ? null : () => _advanceStatus('OUT_FOR_DELIVERY'),
        child: _isUpdating
            ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
            : const Text('Start Delivery'),
      );
    }

    if (status == 'OUT_FOR_DELIVERY') {
      return FilledButton(
        onPressed: _isUpdating ? null : () => _advanceStatus('DELIVERED'),
        child: _isUpdating
            ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
            : const Text('Mark Delivered'),
      );
    }

    return const SizedBox.shrink();
  }
}
