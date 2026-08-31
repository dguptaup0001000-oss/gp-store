import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../design/admin_components.dart';
import '../design/admin_format.dart';
import '../design/admin_tokens.dart';
import 'store_operations_models.dart';
import 'store_operations_providers.dart';

/// How much of the shop's trade happens overnight.
///
/// THE QUESTION THIS ANSWERS is whether the night shift is worth running. It
/// is the only number that tells the owner whether 24-hour ordering earned
/// anything, so it belongs on the dashboard rather than three taps away.
///
/// REAL ROWS, NOT ESTIMATES. Every figure comes from what was stored on each
/// order. Orders placed before the shop had delivery windows are shown in
/// their own band rather than folded into either side - attributing a year of
/// history to whichever bucket was convenient would make the night trade look
/// either enormous or non-existent with no way to tell which from the chart.
class DeliveryTypePanel extends ConsumerWidget {
  const DeliveryTypePanel({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final shares = ref.watch(deliveryTypeSharesProvider);

    return AdminSectionCard(
      title: 'Same-day vs overnight',
      child: shares.when(
        loading: () => const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AdminSkeleton(height: 14, width: 120),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 10),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 10),
          ],
        ),
        error: (error, _) => const Text(
          "Couldn't load the delivery split.",
          style: AdminText.caption,
        ),
        data: (data) {
          final total = data.fold<int>(0, (sum, s) => sum + s.orderCount);
          if (total == 0) {
            return const Text(
              'No orders in this period yet.',
              style: AdminText.caption,
            );
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              for (final share in data) ...[
                _ShareRow(share: share, total: total),
                const SizedBox(height: AdminSpacing.md),
              ],
            ],
          );
        },
      ),
    );
  }
}

class _ShareRow extends StatelessWidget {
  const _ShareRow({required this.share, required this.total});

  final DeliveryTypeShare share;
  final int total;

  @override
  Widget build(BuildContext context) {
    final fraction = total == 0 ? 0.0 : share.orderCount / total;
    // A colour per band, fixed by delivery type rather than by position, so a
    // band that happens to be empty this month does not shift every other
    // colour and make two screenshots incomparable.
    final color = switch (share.deliveryType) {
      'SAME_DAY' => AdminColors.primary,
      'NEXT_MORNING' => AdminColors.warning,
      'MANUAL_SCHEDULED' => AdminColors.success,
      _ => AdminColors.textSecondary,
    };

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                share.label,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: AdminColors.textPrimary,
                ),
              ),
            ),
            Text(
              '${AdminFormat.count(share.orderCount)} · '
              '${AdminFormat.rupeesCompact(share.revenue)}',
              style: AdminText.caption,
            ),
          ],
        ),
        const SizedBox(height: AdminSpacing.xs),
        ClipRRect(
          borderRadius: BorderRadius.circular(4),
          child: LinearProgressIndicator(
            value: fraction,
            minHeight: 8,
            backgroundColor: AdminColors.border,
            valueColor: AlwaysStoppedAnimation<Color>(color),
          ),
        ),
      ],
    );
  }
}
