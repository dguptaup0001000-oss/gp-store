import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_format.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../orders/domain/order_models.dart';
import 'admin_order_detail_screen.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

/// The console's busiest screen.
///
/// PAGINATION, SEARCH AND REFRESH ARE UNCHANGED. The redesign is the
/// presentation: a tone-coded status badge instead of green-or-red text, the
/// shared empty and error states, and a stage filter. The list still pulls
/// further pages while a filter is narrowing it, for the same reason search
/// always did - filtering only the first page would quietly hide orders and
/// look like they had been deleted.
class AdminOrderListScreen extends ConsumerStatefulWidget {
  const AdminOrderListScreen({super.key});

  @override
  ConsumerState<AdminOrderListScreen> createState() => _AdminOrderListScreenState();
}

class _AdminOrderListScreenState extends ConsumerState<AdminOrderListScreen> {
  final _searchController = TextEditingController();
  String _query = '';

  /// null means "every stage". The values are backend OrderStatus names.
  String? _stage;

  /// The stages worth a one-tap filter. Deliberately not every status: a
  /// row of nine chips is not a filter, it is a second navigation problem.
  /// These are the four an operator actually triages by.
  static const _stages = <String, String>{
    'PENDING_CONFIRMATION': 'Pending',
    'CONFIRMED': 'Confirmed',
    'PACKED': 'Packed',
    'OUT_FOR_DELIVERY': 'Out',
  };

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  bool get _isNarrowing => _query.isNotEmpty || _stage != null;

  List<OrderSummary> _filter(List<OrderSummary> orders) {
    var result = orders;
    final stage = _stage;
    if (stage != null) {
      result = result.where((o) => o.orderStatus == stage).toList();
    }
    if (_query.isNotEmpty) {
      final q = _query.toLowerCase();
      result = result.where((o) {
        return o.orderNumber.toLowerCase().contains(q) ||
            (o.customerName?.toLowerCase().contains(q) ?? false);
      }).toList();
    }
    return result;
  }

  @override
  Widget build(BuildContext context) {
    final ordersAsync = ref.watch(adminAllOrdersProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Orders')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
              AdminSpacing.lg,
              AdminSpacing.lg,
              AdminSpacing.lg,
              AdminSpacing.md,
            ),
            child: TextField(
              controller: _searchController,
              onChanged: (value) => setState(() => _query = value.trim()),
              decoration: InputDecoration(
                hintText: 'Search by order number or customer',
                prefixIcon: const Icon(Icons.search, size: 20),
                suffixIcon: _query.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.close, size: 18),
                        tooltip: 'Clear',
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _query = '');
                        },
                      ),
              ),
            ),
          ),
          _StageFilter(
            stages: _stages,
            selected: _stage,
            onSelect: (value) => setState(() => _stage = value),
          ),
          Expanded(
            child: ordersAsync.when(
              loading: () => const _OrderListSkeleton(),
              error: (error, stackTrace) => AdminErrorState(
                // Shows the real failure reason rather than one static
                // string - an admin who can read "connection refused" can
                // act on it, and "something went wrong" helps nobody.
                message: "Couldn't load orders: ${extractErrorMessage(error)}",
                onRetry: hapticize(() => ref.invalidate(adminAllOrdersProvider)),
              ),
              data: (page) {
                final allOrders = page.orders;
                final orders = _filter(allOrders);
                final controller = ref.read(adminAllOrdersProvider.notifier);

                // While a filter is narrowing the list, keep pulling pages so
                // it covers every order rather than whatever page happened to
                // load first.
                if (_isNarrowing && controller.hasMore) {
                  WidgetsBinding.instance
                      .addPostFrameCallback((_) => controller.loadMore());
                }

                if (orders.isEmpty) {
                  return allOrders.isEmpty
                      ? const AdminEmptyState(
                          icon: Icons.receipt_long_outlined,
                          title: 'No orders yet',
                          message:
                              'Orders placed in the shop app will appear here.',
                        )
                      : AdminEmptyState(
                          icon: Icons.search_off_outlined,
                          title: 'No matching orders',
                          message: _stage == null
                              ? 'Try a different order number or customer name.'
                              : 'No orders are at the ${_stages[_stage]} stage right now.',
                          action: TextButton(
                            onPressed: hapticize(() {
                              _searchController.clear();
                              setState(() {
                                _query = '';
                                _stage = null;
                              });
                            }),
                            child: const Text('Clear filters'),
                          ),
                        );
                }

                final hasMore = !_isNarrowing && controller.hasMore;

                return RefreshIndicator(
                  color: AdminColors.primary,
                  onRefresh: () async => ref.invalidate(adminAllOrdersProvider),
                  child: ListView.separated(
                    padding: const EdgeInsets.fromLTRB(
                      AdminSpacing.lg,
                      0,
                      AdminSpacing.lg,
                      AdminSpacing.lg,
                    ),
                    itemCount: orders.length + (hasMore ? 1 : 0),
                    separatorBuilder: (_, __) =>
                        const SizedBox(height: AdminSpacing.sm),
                    itemBuilder: (context, index) {
                      if (index == orders.length) {
                        WidgetsBinding.instance
                            .addPostFrameCallback((_) => controller.loadMore());
                        return const Padding(
                          padding: EdgeInsets.all(AdminSpacing.lg),
                          child: Center(
                            child: SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                          ),
                        );
                      }

                      final order = orders[index];
                      return _OrderCard(
                        order: order,
                        onTap: hapticize(() async {
                          final changed =
                              await Navigator.of(context).push<bool>(
                            MaterialPageRoute(
                              builder: (_) => AdminOrderDetailScreen(
                                  orderId: order.orderId),
                            ),
                          );
                          if (changed == true) {
                            ref.invalidate(adminAllOrdersProvider);
                          }
                        }),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _StageFilter extends StatelessWidget {
  const _StageFilter({
    required this.stages,
    required this.selected,
    required this.onSelect,
  });

  final Map<String, String> stages;
  final String? selected;
  final ValueChanged<String?> onSelect;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 44,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AdminSpacing.lg),
        children: [
          _FilterChip(
            label: 'All',
            selected: selected == null,
            onTap: () => onSelect(null),
          ),
          for (final entry in stages.entries) ...[
            const SizedBox(width: AdminSpacing.sm),
            _FilterChip(
              label: entry.value,
              selected: selected == entry.key,
              // Tapping the active chip clears it. Without this the only way
              // back to "All" is to find that chip again, which on a narrow
              // phone may be scrolled off the left edge.
              onTap: () => onSelect(selected == entry.key ? null : entry.key),
            ),
          ],
        ],
      ),
    );
  }
}

class _FilterChip extends StatelessWidget {
  const _FilterChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Material(
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
      ),
    );
  }
}

class _OrderCard extends StatelessWidget {
  const _OrderCard({required this.order, required this.onTap});

  final OrderSummary order;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AdminColors.surface,
      borderRadius: AdminRadius.card,
      child: InkWell(
        onTap: onTap,
        borderRadius: AdminRadius.card,
        child: Container(
          padding: const EdgeInsets.all(AdminSpacing.lg),
          decoration: BoxDecoration(
            borderRadius: AdminRadius.card,
            border: Border.all(color: AdminColors.border),
            boxShadow: AdminShadows.card,
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            '#${order.orderNumber}',
                            style: const TextStyle(
                              fontWeight: FontWeight.w700,
                              color: AdminColors.textPrimary,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        const SizedBox(width: AdminSpacing.sm),
                        Text(
                          AdminFormat.rupees(order.totalAmount),
                          style: AdminText.numeric,
                        ),
                      ],
                    ),
                    if (order.customerName != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        order.customerName!,
                        style: AdminText.caption,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                    const SizedBox(height: AdminSpacing.sm),
                    AdminStatusBadge(
                      label:
                          AdminStatusBadge.humanizeStatus(order.orderStatus),
                      tone: AdminStatusBadge.toneForOrderStatus(
                          order.orderStatus),
                      dense: true,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: AdminSpacing.sm),
              const Icon(Icons.chevron_right,
                  color: AdminColors.textMuted, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

/// Skeleton rows rather than a lone spinner. The list's shape appears
/// immediately, so the screen does not jump when the data lands.
class _OrderListSkeleton extends StatelessWidget {
  const _OrderListSkeleton();

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(
        AdminSpacing.lg,
        0,
        AdminSpacing.lg,
        AdminSpacing.lg,
      ),
      itemCount: 6,
      separatorBuilder: (_, __) => const SizedBox(height: AdminSpacing.sm),
      itemBuilder: (_, __) => Container(
        padding: const EdgeInsets.all(AdminSpacing.lg),
        decoration: BoxDecoration(
          color: AdminColors.surface,
          borderRadius: AdminRadius.card,
          border: Border.all(color: AdminColors.border),
        ),
        child: const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AdminSkeleton(height: 14, width: 140),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 10, width: 90),
            SizedBox(height: AdminSpacing.sm),
            AdminSkeleton(height: 16, width: 70),
          ],
        ),
      ),
    );
  }
}
