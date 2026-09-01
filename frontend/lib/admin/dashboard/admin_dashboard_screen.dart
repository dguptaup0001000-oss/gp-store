import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/util/haptic_widgets.dart';
import '../../features/admin/domain/analytics_models.dart';
import '../../features/admin/presentation/admin_inventory_screen.dart';
import '../../features/admin/presentation/admin_order_list_screen.dart';
import '../../features/admin/presentation/admin_providers.dart';
import '../design/admin_components.dart';
import '../design/admin_format.dart';
import '../design/admin_tokens.dart';
import '../operations/delivery_type_panel.dart';
import 'admin_live_clock_panel.dart';
import 'admin_revenue_chart.dart';

/// The console's home screen.
///
/// A BODY, NOT A SCAFFOLD. AdminShell owns the app bar on a phone and the
/// pane header on a wide layout; if this brought its own the two would stack.
///
/// EVERY PANEL LOADS INDEPENDENTLY. Five providers back this screen and each
/// one renders its own skeleton, empty and error state inside its own card.
/// One slow or failing endpoint therefore costs one panel, not the whole
/// dashboard - which matters because a shopkeeper opening this at 8am wants
/// today's order count even if the analytics query is having a bad minute.
class AdminDashboardScreen extends ConsumerWidget {
  const AdminDashboardScreen({super.key});

  Future<void> _refresh(WidgetRef ref) async {
    ref.invalidate(adminSalesSummaryProvider);
    ref.invalidate(adminSalesSeriesProvider);
    ref.invalidate(adminOrderStatusBreakdownProvider);
    ref.invalidate(adminTopProductsProvider);
    ref.invalidate(adminLowStockCountProvider);
    // Await one of them so the pull-to-refresh spinner stays up until there
    // is actually something new to look at, instead of snapping back while
    // the panels are still empty.
    await ref.read(adminSalesSummaryProvider.future);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return RefreshIndicator(
      color: AdminColors.primary,
      onRefresh: () => _refresh(ref),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final wide = constraints.maxWidth >= AdminBreakpoints.medium;
          return ListView(
            padding: const EdgeInsets.all(AdminSpacing.lg),
            children: [
              // Above the period selector because it is the one panel that is
              // not about a period at all - it answers "what is happening now",
              // which is the first thing a shopkeeper opening this wants.
              const AdminLiveClockPanel(),
              const SizedBox(height: AdminSpacing.lg),
              const _PeriodSelector(),
              const SizedBox(height: AdminSpacing.lg),
              _KpiGrid(wide: wide),
              const SizedBox(height: AdminSpacing.lg),
              const _RevenuePanel(),
              const SizedBox(height: AdminSpacing.lg),
              const _OrderStatusPanel(),
              const SizedBox(height: AdminSpacing.lg),
              const _TopProductsPanel(),
              const SizedBox(height: AdminSpacing.lg),
              // Whether the night shift earned anything. Reads the same period
              // provider as everything above it, so the two can never be
              // showing different windows.
              const DeliveryTypePanel(),
              const SizedBox(height: AdminSpacing.xxl),
            ],
          );
        },
      ),
    );
  }
}

/// 7 / 30 / 90 days. Drives the summary, the chart and the leaderboard from
/// one provider, so the three can never be showing different windows.
class _PeriodSelector extends ConsumerWidget {
  const _PeriodSelector();

  static const _options = [7, 30, 90];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(analyticsPeriodDaysProvider);
    return Row(
      children: [
        for (final days in _options) ...[
          _Chip(
            label: days == 7 ? '7 days' : '$days days',
            selected: days == selected,
            onTap: () =>
                ref.read(analyticsPeriodDaysProvider.notifier).state = days,
          ),
          const SizedBox(width: AdminSpacing.sm),
        ],
      ],
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? AdminColors.primaryLight : AdminColors.surface,
      borderRadius: AdminRadius.badge,
      child: InkWell(
        onTap: hapticize(onTap),
        borderRadius: AdminRadius.badge,
        child: Container(
          padding: const EdgeInsets.symmetric(
            horizontal: AdminSpacing.lg,
            vertical: AdminSpacing.sm,
          ),
          decoration: BoxDecoration(
            borderRadius: AdminRadius.badge,
            border: Border.all(
              color: selected ? AdminColors.primary : AdminColors.border,
            ),
          ),
          child: Text(
            label,
            style: TextStyle(
              fontSize: 13,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              color: selected
                  ? AdminColors.primaryDeep
                  : AdminColors.textSecondary,
            ),
          ),
        ),
      ),
    );
  }
}

class _KpiGrid extends ConsumerWidget {
  const _KpiGrid({required this.wide});

  final bool wide;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final summary = ref.watch(adminSalesSummaryProvider);
    final lowStock = ref.watch(adminLowStockCountProvider);

    return summary.when(
      loading: () => _grid(const [
        _KpiSkeleton(),
        _KpiSkeleton(),
        _KpiSkeleton(),
        _KpiSkeleton(),
      ]),
      error: (error, _) => AdminSectionCard(
        child: AdminErrorState(
          message: 'Could not load sales figures.',
          compact: true,
          onRetry: () => ref.invalidate(adminSalesSummaryProvider),
        ),
      ),
      data: (data) => _grid([
        AdminKpiCard(
          icon: Icons.currency_rupee_rounded,
          label: 'Revenue',
          value: AdminFormat.rupees(data.revenue),
          deltaPercent: _deltaOrNull(data.previousRevenue, data.revenueChangePercent),
          comparisonLabel: 'vs previous ${data.periodDays} days',
        ),
        AdminKpiCard(
          icon: Icons.receipt_long_outlined,
          label: 'Orders',
          value: AdminFormat.count(data.orderCount),
          deltaPercent: _deltaOrNull(
              data.previousOrderCount.toDouble(), data.orderCountChangePercent),
          comparisonLabel: 'vs previous ${data.periodDays} days',
          onTap: () => Navigator.of(context).push(MaterialPageRoute(
              builder: (_) => const AdminOrderListScreen())),
        ),
        AdminKpiCard(
          icon: Icons.shopping_bag_outlined,
          label: 'Average order',
          value: AdminFormat.rupees(data.averageOrderValue),
        ),
        // Cancellations rising is bad news, so this card inverts the colour
        // rule - otherwise a growing number of cancelled orders would show
        // as a cheerful green arrow.
        AdminKpiCard(
          icon: Icons.cancel_outlined,
          label: 'Cancelled',
          value: AdminFormat.count(data.cancelledCount),
          higherIsBetter: false,
        ),
        lowStock.when(
          loading: () => const _KpiSkeleton(),
          error: (_, __) => const SizedBox.shrink(),
          data: (count) => AdminKpiCard(
            icon: Icons.warning_amber_rounded,
            label: 'Low stock items',
            value: AdminFormat.count(count),
            higherIsBetter: false,
            onTap: () => Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => const AdminInventoryScreen())),
          ),
        ),
      ]),
    );
  }

  /// A percentage against a zero baseline is not information - the backend
  /// returns 0 for it, and showing "0.0%" beside a first-ever week implies a
  /// comparison that was never made. Null tells the card to render nothing.
  static double? _deltaOrNull(double previous, double percent) =>
      previous == 0 ? null : percent;

  Widget _grid(List<Widget> cards) {
    // Two columns on a phone, four when there is room. A single column would
    // push the chart below the fold on every device.
    final columns = wide ? 4 : 2;
    return GridView.count(
      crossAxisCount: columns,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      crossAxisSpacing: AdminSpacing.md,
      mainAxisSpacing: AdminSpacing.md,
      childAspectRatio: wide ? 1.7 : 1.35,
      children: cards,
    );
  }
}

class _KpiSkeleton extends StatelessWidget {
  const _KpiSkeleton();

  @override
  Widget build(BuildContext context) {
    return Container(
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
    );
  }
}

class _RevenuePanel extends ConsumerWidget {
  const _RevenuePanel();

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
        error: (error, _) => AdminErrorState(
          message: 'Could not load the sales chart.',
          compact: true,
          onRetry: () => ref.invalidate(adminSalesSeriesProvider),
        ),
        data: (points) => AdminRevenueChart(points: points),
      ),
    );
  }
}

class _OrderStatusPanel extends ConsumerWidget {
  const _OrderStatusPanel();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final breakdown = ref.watch(adminOrderStatusBreakdownProvider);

    return AdminSectionCard(
      title: 'Orders by stage',
      subtitle: 'What needs attention right now',
      child: breakdown.when(
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
        error: (error, _) => AdminErrorState(
          message: 'Could not load the order breakdown.',
          compact: true,
          onRetry: () => ref.invalidate(adminOrderStatusBreakdownProvider),
        ),
        data: (data) {
          if (data.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.inbox_outlined,
              title: 'No orders yet',
              message: 'Stages will appear here once the shop takes an order.',
            );
          }
          final entries = data.entries.toList()
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
    final tone = AdminStatusBadge.toneForOrderStatus(status);
    return Row(
      children: [
        SizedBox(
          width: 130,
          child: AdminStatusBadge(
            label: AdminStatusBadge.humanizeStatus(status),
            tone: tone,
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

class _TopProductsPanel extends ConsumerWidget {
  const _TopProductsPanel();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final products = ref.watch(adminTopProductsProvider);
    final days = ref.watch(analyticsPeriodDaysProvider);

    return AdminSectionCard(
      title: 'Top products',
      subtitle: 'By units sold in the last $days days',
      child: products.when(
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
        error: (error, _) => AdminErrorState(
          message: 'Could not load top products.',
          compact: true,
          onRetry: () => ref.invalidate(adminTopProductsProvider),
        ),
        data: (data) {
          if (data.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.leaderboard_outlined,
              title: 'Nothing sold in this period',
              message: 'Try a longer period, or come back after the next order.',
            );
          }
          return Column(
            children: [
              for (var i = 0; i < data.length && i < 8; i++) ...[
                _TopProductRow(rank: i + 1, product: data[i]),
                if (i < data.length - 1 && i < 7)
                  const Divider(height: AdminSpacing.lg, color: AdminColors.border),
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
                    // A product photograph failing to load must never take
                    // the dashboard with it - the URL is a short-lived
                    // signed GET and can expire while the screen is open.
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
              Text('#$rank  •  ${AdminFormat.count(product.unitsSold)} units',
                  style: AdminText.caption),
            ],
          ),
        ),
        const SizedBox(width: AdminSpacing.sm),
        Text(AdminFormat.rupees(product.revenue), style: AdminText.numeric),
      ],
    );
  }
}
