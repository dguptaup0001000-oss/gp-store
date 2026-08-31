import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/dashboard/admin_revenue_chart.dart';
import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_format.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/analytics_models.dart';
import 'admin_inventory_screen.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

/// The deep view of the same numbers the dashboard summarises.
///
/// SHARES THE DASHBOARD'S COMPONENTS AND PROVIDERS ON PURPOSE. Before this
/// there were two implementations of "revenue, order status, top products",
/// and they had already drifted: this screen printed unitsSold from a query
/// that counted order lines, and formatted rupees with toStringAsFixed so a
/// month's takings read as 145000 rather than 1,45,000. Two screens showing
/// the same figure differently is worse than either one alone, because now
/// the operator has to decide which to believe.
///
/// What it keeps that the dashboard does not: the whole revenue series in
/// one place, a low-stock call to action, and a longer leaderboard.
class AdminAnalyticsScreen extends ConsumerWidget {
  const AdminAnalyticsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final periodDays = ref.watch(analyticsPeriodDaysProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Analytics')),
      body: RefreshIndicator(
        color: AdminColors.primary,
        onRefresh: () async {
          ref.invalidate(adminSalesSummaryProvider);
          ref.invalidate(adminSalesSeriesProvider);
          ref.invalidate(adminOrderStatusBreakdownProvider);
          ref.invalidate(adminTopProductsProvider);
          ref.invalidate(adminLowStockCountProvider);
          await ref.read(adminSalesSummaryProvider.future);
        },
        child: ListView(
          padding: const EdgeInsets.all(AdminSpacing.lg),
          children: [
            _PeriodSelector(
              selectedDays: periodDays,
              onChanged: (days) =>
                  ref.read(analyticsPeriodDaysProvider.notifier).state = days,
            ),
            const SizedBox(height: AdminSpacing.lg),
            const _LowStockAlert(),
            const _SalesSummarySection(),
            const SizedBox(height: AdminSpacing.lg),
            const _RevenueSection(),
            const SizedBox(height: AdminSpacing.lg),
            const _OrderStatusBreakdown(),
            const SizedBox(height: AdminSpacing.lg),
            const _TopProductsList(),
            const SizedBox(height: AdminSpacing.xxl),
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

  /// A percentage against a zero baseline is not information. The backend
  /// returns 0 for it; showing "0.0%" beside a first-ever week implies a
  /// comparison that was never made, so the card is told to show nothing.
  static double? _deltaOrNull(double previous, double percent) =>
      previous == 0 ? null : percent;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final summaryAsync = ref.watch(adminSalesSummaryProvider);

    return summaryAsync.when(
      loading: () => const _KpiSkeletonGrid(),
      error: (error, stackTrace) => AdminSectionCard(
        child: AdminErrorState(
          message: "Couldn't load sales data: ${extractErrorMessage(error)}",
          compact: true,
          onRetry: hapticize(() => ref.invalidate(adminSalesSummaryProvider)),
        ),
      ),
      data: (summary) => GridView.count(
        crossAxisCount: 2,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        mainAxisSpacing: AdminSpacing.md,
        crossAxisSpacing: AdminSpacing.md,
        childAspectRatio: 1.35,
        children: [
          AdminKpiCard(
            icon: Icons.currency_rupee_rounded,
            label: 'Revenue',
            value: AdminFormat.rupees(summary.revenue),
            deltaPercent: _deltaOrNull(
                summary.previousRevenue, summary.revenueChangePercent),
            comparisonLabel: 'vs previous ${summary.periodDays} days',
          ),
          AdminKpiCard(
            icon: Icons.receipt_long_outlined,
            label: 'Orders',
            value: AdminFormat.count(summary.orderCount),
            deltaPercent: _deltaOrNull(summary.previousOrderCount.toDouble(),
                summary.orderCountChangePercent),
            comparisonLabel: 'vs previous ${summary.periodDays} days',
          ),
          AdminKpiCard(
            icon: Icons.shopping_bag_outlined,
            label: 'Average order',
            value: AdminFormat.rupees(summary.averageOrderValue),
          ),
          // Cancellations rising is bad news, so this card inverts the
          // colour rule - otherwise more cancelled orders would show as a
          // cheerful green arrow.
          AdminKpiCard(
            icon: Icons.cancel_outlined,
            label: 'Cancelled',
            value: AdminFormat.count(summary.cancelledCount),
            higherIsBetter: false,
          ),
        ],
      ),
    );
  }
}

class _RevenueSection extends ConsumerWidget {
  const _RevenueSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final series = ref.watch(adminSalesSeriesProvider);
    final days = ref.watch(analyticsPeriodDaysProvider);

    return AdminSectionCard(
      title: 'Revenue',
      subtitle: 'Daily takings over the last $days days',
      child: series.when(
        loading: () => const SizedBox(
          height: 160,
          child: Center(child: AdminSkeleton(height: 120)),
        ),
        error: (error, stackTrace) => AdminErrorState(
          message: "Couldn't load the sales chart: ${extractErrorMessage(error)}",
          compact: true,
          onRetry: hapticize(() => ref.invalidate(adminSalesSeriesProvider)),
        ),
        data: (points) => AdminRevenueChart(points: points, height: 180),
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

        return Padding(
          padding: const EdgeInsets.only(bottom: AdminSpacing.lg),
          child: Material(
            color: AdminColors.dangerBg,
            borderRadius: AdminRadius.card,
            child: InkWell(
              borderRadius: AdminRadius.card,
              onTap: hapticize(() => Navigator.of(context).push(
                    MaterialPageRoute(
                        builder: (_) => const AdminInventoryScreen()),
                  )),
              child: Padding(
                padding: const EdgeInsets.all(AdminSpacing.lg),
                child: Row(
                  children: [
                    const Icon(Icons.warning_amber_rounded,
                        color: AdminColors.danger),
                    const SizedBox(width: AdminSpacing.md),
                    Expanded(
                      child: Text(
                        '$count item${count == 1 ? '' : 's'} running low on stock',
                        style: const TextStyle(
                          color: AdminColors.danger,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    const Icon(Icons.chevron_right, color: AdminColors.danger),
                  ],
                ),
              ),
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

    return AdminSectionCard(
      title: 'Orders by stage',
      subtitle: 'What needs attention right now',
      child: breakdownAsync.when(
        loading: () => const Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AdminSkeleton(height: 18),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 18),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 18),
          ],
        ),
        error: (error, stackTrace) => AdminErrorState(
          message:
              "Couldn't load order status data: ${extractErrorMessage(error)}",
          compact: true,
          onRetry:
              hapticize(() => ref.invalidate(adminOrderStatusBreakdownProvider)),
        ),
        data: (breakdown) {
          if (breakdown.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.inbox_outlined,
              title: 'No orders yet',
              message: 'Stages appear here once the shop takes an order.',
            );
          }

          // Sorted by size, and scaled against the TOTAL rather than the
          // largest bucket. Scaling to the peak made the biggest stage a
          // full bar every time, which says nothing about whether it is
          // most of the orders or barely more than the next one.
          final entries = breakdown.entries.toList()
            ..sort((a, b) => b.value.compareTo(a.value));
          final total = entries.fold<int>(0, (sum, e) => sum + e.value);

          return Column(
            children: [
              for (final entry in entries) ...[
                _StatusRow(
                  status: entry.key,
                  count: entry.value,
                  fraction: total == 0 ? 0 : entry.value / total,
                ),
                const SizedBox(height: AdminSpacing.md),
              ],
            ],
          );
        },
      ),
    );
  }
}

class _StatusRow extends StatelessWidget {
  const _StatusRow({
    required this.status,
    required this.count,
    required this.fraction,
  });

  final String status;
  final int count;
  final double fraction;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(
          width: 130,
          child: AdminStatusBadge(
            label: AdminStatusBadge.humanizeStatus(status),
            tone: AdminStatusBadge.toneForOrderStatus(status),
            dense: true,
          ),
        ),
        Expanded(
          child: ClipRRect(
            borderRadius: BorderRadius.circular(AdminRadius.sm),
            child: LinearProgressIndicator(
              value: fraction.clamp(0.0, 1.0),
              minHeight: 8,
              backgroundColor: AdminColors.neutralBg,
              valueColor:
                  const AlwaysStoppedAnimation<Color>(AdminColors.primary),
            ),
          ),
        ),
        const SizedBox(width: AdminSpacing.md),
        SizedBox(
          width: 44,
          child: Text(
            AdminFormat.count(count),
            style: AdminText.numeric,
            textAlign: TextAlign.right,
          ),
        ),
      ],
    );
  }
}

class _TopProductsList extends ConsumerWidget {
  const _TopProductsList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final productsAsync = ref.watch(adminTopProductsProvider);
    final days = ref.watch(analyticsPeriodDaysProvider);

    return AdminSectionCard(
      title: 'Top products',
      subtitle: 'By units sold in the last $days days',
      child: productsAsync.when(
        loading: () => const Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AdminSkeleton(height: 44),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 44),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 44),
          ],
        ),
        error: (error, stackTrace) => AdminErrorState(
          message: "Couldn't load top products: ${extractErrorMessage(error)}",
          compact: true,
          onRetry: hapticize(() => ref.invalidate(adminTopProductsProvider)),
        ),
        data: (products) {
          if (products.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.leaderboard_outlined,
              title: 'Nothing sold in this period',
              message: 'Try a longer period, or come back after the next order.',
            );
          }

          return Column(
            children: [
              for (var i = 0; i < products.length; i++) ...[
                _TopProductRow(rank: i + 1, product: products[i]),
                if (i < products.length - 1)
                  const Divider(
                      height: AdminSpacing.lg, color: AdminColors.border),
              ],
            ],
          );
        },
      ),
    );
  }
}

class _TopProductRow extends StatelessWidget {
  const _TopProductRow({required this.rank, required this.product});

  final int rank;
  final TopProduct product;

  @override
  Widget build(BuildContext context) {
    final imageUrl = product.imageUrl;
    return Row(
      children: [
        SizedBox(
          width: 24,
          child: Text(
            '$rank',
            style: AdminText.caption,
            textAlign: TextAlign.center,
          ),
        ),
        const SizedBox(width: AdminSpacing.sm),
        ClipRRect(
          borderRadius: BorderRadius.circular(AdminRadius.md),
          child: SizedBox(
            width: 40,
            height: 40,
            child: imageUrl == null || imageUrl.isEmpty
                ? const ColoredBox(
                    color: AdminColors.neutralBg,
                    child: Icon(Icons.image_not_supported_outlined,
                        size: 18, color: AdminColors.textMuted),
                  )
                : Image.network(
                    imageUrl,
                    fit: BoxFit.cover,
                    // A signed image URL can expire while this screen is
                    // open. A broken photograph must not take the page down.
                    errorBuilder: (_, __, ___) => const ColoredBox(
                      color: AdminColors.neutralBg,
                      child: Icon(Icons.broken_image_outlined,
                          size: 18, color: AdminColors.textMuted),
                    ),
                  ),
          ),
        ),
        const SizedBox(width: AdminSpacing.md),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                product.productName,
                style: AdminText.body,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 2),
              Text(
                '${AdminFormat.count(product.unitsSold)} units',
                style: AdminText.caption,
              ),
            ],
          ),
        ),
        const SizedBox(width: AdminSpacing.sm),
        Text(AdminFormat.rupees(product.revenue),
            style: AdminText.numeric),
      ],
    );
  }
}

class _KpiSkeletonGrid extends StatelessWidget {
  const _KpiSkeletonGrid();

  @override
  Widget build(BuildContext context) {
    return GridView.count(
      crossAxisCount: 2,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: AdminSpacing.md,
      crossAxisSpacing: AdminSpacing.md,
      childAspectRatio: 1.35,
      children: List.generate(
        4,
        (_) => Container(
          padding: const EdgeInsets.all(AdminSpacing.lg),
          decoration: BoxDecoration(
            color: AdminColors.surface,
            borderRadius: AdminRadius.card,
            border: Border.all(color: AdminColors.border),
          ),
          child: const Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              AdminSkeleton(height: 12, width: 60),
              AdminSkeleton(height: 22, width: 90),
              AdminSkeleton(height: 10, width: 70),
            ],
          ),
        ),
      ),
    );
  }
}
