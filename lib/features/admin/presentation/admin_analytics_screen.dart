import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import 'admin_inventory_screen.dart';
import 'admin_providers.dart';

class AdminAnalyticsScreen extends ConsumerWidget {
  const AdminAnalyticsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final periodDays = ref.watch(analyticsPeriodDaysProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Analytics')),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(adminSalesSummaryProvider);
          ref.invalidate(adminOrderStatusBreakdownProvider);
          ref.invalidate(adminTopProductsProvider);
          ref.invalidate(adminLowStockCountProvider);
        },
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _PeriodSelector(
              selectedDays: periodDays,
              onChanged: (days) => ref.read(analyticsPeriodDaysProvider.notifier).state = days,
            ),
            const SizedBox(height: 16),
            const _SalesSummarySection(),
            const SizedBox(height: 24),
            const _LowStockAlert(),
            const SizedBox(height: 24),
            Text('Order Status', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            const _OrderStatusBreakdown(),
            const SizedBox(height: 24),
            Text('Top Products', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            const _TopProductsList(),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }
}

class _PeriodSelector extends StatelessWidget {
  const _PeriodSelector({required this.selectedDays, required this.onChanged});

  final int selectedDays;
  final void Function(int days) onChanged;

  @override
  Widget build(BuildContext context) {
    return SegmentedButton<int>(
      segments: const [
        ButtonSegment(value: 7, label: Text('7 days')),
        ButtonSegment(value: 30, label: Text('30 days')),
        ButtonSegment(value: 90, label: Text('90 days')),
      ],
      selected: {selectedDays},
      onSelectionChanged: (selection) => onChanged(selection.first),
    );
  }
}

class _SalesSummarySection extends ConsumerWidget {
  const _SalesSummarySection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final summaryAsync = ref.watch(adminSalesSummaryProvider);

    return summaryAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, stackTrace) => Column(
        children: [
          Text(extractErrorMessage(error), textAlign: TextAlign.center),
          TextButton(onPressed: () => ref.invalidate(adminSalesSummaryProvider), child: const Text('Retry')),
        ],
      ),
      data: (summary) => GridView.count(
        crossAxisCount: 2,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 1.6,
        children: [
          _statCard('Revenue', '₹${summary.revenue.toStringAsFixed(0)}', AppColors.primary),
          _statCard('Orders', '${summary.orderCount}', AppColors.textPrimary),
          _statCard('Avg Order Value', '₹${summary.averageOrderValue.toStringAsFixed(0)}', AppColors.textPrimary),
          _statCard('Cancelled', '${summary.cancelledCount}', summary.cancelledCount > 0 ? AppColors.error : AppColors.textPrimary),
        ],
      ),
    );
  }

  Widget _statCard(String label, String value, Color valueColor) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(value, style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: valueColor)),
          const SizedBox(height: 4),
          Text(label, style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
        ],
      ),
    );
  }
}

class _LowStockAlert extends ConsumerWidget {
  const _LowStockAlert();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final countAsync = ref.watch(adminLowStockCountProvider);

    return countAsync.when(
      loading: () => const SizedBox.shrink(),
      error: (error, stackTrace) => const SizedBox.shrink(),
      data: (count) {
        if (count == 0) return const SizedBox.shrink();

        return InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const AdminInventoryScreen()),
          ),
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: AppColors.error.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                const Icon(Icons.warning_amber_rounded, color: AppColors.error),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    '$count item${count == 1 ? '' : 's'} running low on stock',
                    style: const TextStyle(color: AppColors.error, fontWeight: FontWeight.w600),
                  ),
                ),
                const Icon(Icons.chevron_right, color: AppColors.error),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _OrderStatusBreakdown extends ConsumerWidget {
  const _OrderStatusBreakdown();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final breakdownAsync = ref.watch(adminOrderStatusBreakdownProvider);

    return breakdownAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, stackTrace) => Column(
        children: [
          Text(extractErrorMessage(error), textAlign: TextAlign.center),
          TextButton(onPressed: () => ref.invalidate(adminOrderStatusBreakdownProvider), child: const Text('Retry')),
        ],
      ),
      data: (breakdown) {
        if (breakdown.isEmpty) {
          return const Text('No orders yet', style: TextStyle(color: AppColors.textSecondary));
        }

        final maxCount = breakdown.values.reduce((a, b) => a > b ? a : b);

        return Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
          child: Column(
            children: breakdown.entries.map((entry) {
              final fraction = maxCount == 0 ? 0.0 : entry.value / maxCount;
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(entry.key.replaceAll('_', ' '), style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
                        Text('${entry.value}', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
                      ],
                    ),
                    const SizedBox(height: 4),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: LinearProgressIndicator(
                        value: fraction,
                        minHeight: 6,
                        backgroundColor: AppColors.background,
                        valueColor: const AlwaysStoppedAnimation(AppColors.primary),
                      ),
                    ),
                  ],
                ),
              );
            }).toList(),
          ),
        );
      },
    );
  }
}

class _TopProductsList extends ConsumerWidget {
  const _TopProductsList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final productsAsync = ref.watch(adminTopProductsProvider);

    return productsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, stackTrace) => Column(
        children: [
          Text(extractErrorMessage(error), textAlign: TextAlign.center),
          TextButton(onPressed: () => ref.invalidate(adminTopProductsProvider), child: const Text('Retry')),
        ],
      ),
      data: (products) {
        if (products.isEmpty) {
          return const Text('No sales in this period yet', style: TextStyle(color: AppColors.textSecondary));
        }

        return Container(
          padding: const EdgeInsets.symmetric(vertical: 4),
          decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
          child: Column(
            children: products.asMap().entries.map((entry) {
              final rank = entry.key + 1;
              final product = entry.value;
              return ListTile(
                leading: CircleAvatar(
                  radius: 14,
                  backgroundColor: rank <= 3 ? AppColors.primary : AppColors.background,
                  child: Text('$rank', style: TextStyle(fontSize: 12, color: rank <= 3 ? Colors.white : AppColors.textSecondary)),
                ),
                title: Text(product.productName, style: const TextStyle(fontSize: 14)),
                trailing: Text('${product.unitsSold} sold', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 12)),
              );
            }).toList(),
          ),
        );
      },
    );
  }
}
