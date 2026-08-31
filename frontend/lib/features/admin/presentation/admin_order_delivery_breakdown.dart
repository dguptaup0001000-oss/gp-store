import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_tokens.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/delivery_pricing_models.dart';
import 'admin_providers.dart';

/// Read-only delivery breakdown for one admin order, from stored columns.
class AdminOrderDeliveryBreakdownCard extends ConsumerWidget {
  const AdminOrderDeliveryBreakdownCard({super.key, required this.orderId});

  final int orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final breakdownAsync = ref.watch(adminOrderDeliveryBreakdownProvider(orderId));

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AdminColors.surface, borderRadius: BorderRadius.circular(12)),
      child: breakdownAsync.when(
        loading: () => const Padding(
          padding: EdgeInsets.symmetric(vertical: 8),
          child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
        ),
        error: (error, stackTrace) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Delivery charge', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text("Couldn't load the stored delivery breakdown: ${extractErrorMessage(error)}"),
            TextButton(
              onPressed: hapticize(() => ref.invalidate(adminOrderDeliveryBreakdownProvider(orderId))),
              child: const Text('Retry'),
            ),
          ],
        ),
        data: (breakdown) => _BreakdownBody(breakdown: breakdown),
      ),
    );
  }
}

class _BreakdownBody extends StatelessWidget {
  const _BreakdownBody({required this.breakdown});

  final DeliveryOrderBreakdown breakdown;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Delivery charge', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        Text(
          'Figures stored when this order was placed. They are not recalculated if pricing rules have changed since.',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(color: AdminColors.textSecondary),
        ),
        const SizedBox(height: 12),
        if (!breakdown.pricedByCurrentSystem)
          const Text(
            'This order was placed before the current delivery pricing system, so there is no stored breakdown.',
          )
        else ...[
          _row('Distance', _km(breakdown.distanceKm)),
          _row('Weight', _kg(breakdown.totalWeightKg)),
          _row('Distance charge', _rupees(breakdown.distanceCharge)),
          _row('Weight charge', _rupees(breakdown.weightCharge)),
          _row('Normal delivery charge', _rupees(breakdown.normalDeliveryCharge)),
          _row('Order profit', _rupees(breakdown.availableProfit)),
          _row('Profit needed for free delivery', _rupees(breakdown.freeDeliveryRequiredProfit)),
          _row('Free delivery', breakdown.freeDelivery ? 'Yes' : 'No'),
          _row('Subsidy', _rupees(breakdown.subsidy)),
          _row('Final delivery charge', _rupees(breakdown.finalDeliveryCharge), emphasize: true),
        ],
        if (breakdown.notes != null && breakdown.notes!.trim().isNotEmpty) ...[
          const SizedBox(height: 12),
          Text('Notes', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          Text(breakdown.notes!, style: Theme.of(context).textTheme.bodyMedium),
        ],
      ],
    );
  }

  Widget _row(String label, String value, {bool emphasize = false}) {
    final style = emphasize ? const TextStyle(fontWeight: FontWeight.w700) : null;
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: Text(label, style: style)),
          Text(value, style: style),
        ],
      ),
    );
  }

  static String _rupees(double? value) {
    if (value == null) return '—';
    return '₹${value.toStringAsFixed(2)}';
  }

  static String _km(double? value) {
    if (value == null) return '—';
    return '${value.toStringAsFixed(2)} km';
  }

  static String _kg(double? value) {
    if (value == null) return '—';
    return '${value.toStringAsFixed(3)} kg';
  }
}
