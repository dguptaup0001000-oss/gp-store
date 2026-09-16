import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/api/error_messages.dart';
import '../domain/control_tower_models.dart';
import 'platform_entity_360_screen.dart';
import 'platform_resource_screen.dart';
import 'platform_providers.dart';

/// Platform-wide landing screen. All totals and search results come from
/// bounded, platform-authorized backend projections; this widget never loads
/// orders/customers into memory to calculate a headline.
class PlatformControlTowerScreen extends ConsumerStatefulWidget {
  const PlatformControlTowerScreen({super.key});

  @override
  ConsumerState<PlatformControlTowerScreen> createState() =>
      _PlatformControlTowerScreenState();
}

class _PlatformControlTowerScreenState
    extends ConsumerState<PlatformControlTowerScreen> {
  final _search = TextEditingController();
  Timer? _debounce;
  Future<PlatformDashboardSummary>? _dashboard;
  PlatformSearchPage? _results;
  Object? _searchError;
  bool _searching = false;
  int _rangeDays = 1;
  DateTimeRange? _customRange;

  @override
  void initState() {
    super.initState();
    _reloadDashboard();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  DateTimeRange get _range {
    if (_customRange != null) return _customRange!;
    final today = DateTime.now();
    final day = DateTime(today.year, today.month, today.day);
    return DateTimeRange(start: day.subtract(Duration(days: _rangeDays - 1)), end: day);
  }

  void _reloadDashboard() {
    final range = _range;
    setState(() {
      _dashboard = ref.read(platformRepositoryProvider).controlTowerDashboard(
            from: range.start,
            to: range.end,
          );
    });
  }

  void _onSearch(String value) {
    _debounce?.cancel();
    final query = value.trim();
    if (query.length < 2) {
      setState(() {
        _results = null;
        _searchError = null;
        _searching = false;
      });
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 350), () => _runSearch(query));
  }

  Future<void> _runSearch(String query, {int page = 0, bool append = false}) async {
    setState(() {
      _searching = true;
      _searchError = null;
    });
    try {
      final next = await ref.read(platformRepositoryProvider).globalSearch(
            query: query,
            page: page,
          );
      if (!mounted || _search.text.trim() != query) return;
      setState(() {
        if (append && _results != null) {
          _results = PlatformSearchPage(
            content: [..._results!.content, ...next.content],
            page: next.page,
            totalPages: next.totalPages,
            totalElements: next.totalElements,
          );
        } else {
          _results = next;
        }
      });
    } catch (error) {
      if (mounted) setState(() => _searchError = error);
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AdminColors.background,
      body: RefreshIndicator(
        onRefresh: () async => _reloadDashboard(),
        child: ListView(
          padding: const EdgeInsets.all(AdminSpacing.lg),
          children: [
            _searchBox(),
            if (_search.text.trim().length >= 2) ...[
              const SizedBox(height: AdminSpacing.md),
              _searchResults(),
            ],
            const SizedBox(height: AdminSpacing.xl),
            _rangePicker(),
            const SizedBox(height: AdminSpacing.lg),
            FutureBuilder<PlatformDashboardSummary>(
              future: _dashboard,
              builder: (context, snapshot) {
                if (snapshot.connectionState == ConnectionState.waiting) {
                  return const Center(child: CircularProgressIndicator(strokeWidth: 2));
                }
                if (snapshot.hasError) {
                  return _ErrorCard(
                    message: extractErrorMessage(snapshot.error!),
                    onRetry: _reloadDashboard,
                  );
                }
                final summary = snapshot.data;
                if (summary == null) {
                  return const Text('No platform summary is available.');
                }
                return _Dashboard(summary: summary);
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _searchBox() => TextField(
        controller: _search,
        onChanged: (value) {
          setState(() {});
          _onSearch(value);
        },
        decoration: InputDecoration(
          hintText: 'Search customers, merchants, shops, orders, workers, products',
          prefixIcon: const Icon(Icons.search_rounded),
          suffixIcon: _search.text.isEmpty
              ? null
              : IconButton(
                  tooltip: 'Clear search',
                  onPressed: () {
                    _search.clear();
                    _onSearch('');
                  },
                  icon: const Icon(Icons.close_rounded),
                ),
          filled: true,
          fillColor: AdminColors.surface,
          border: const OutlineInputBorder(borderRadius: AdminRadius.control),
        ),
      );

  Widget _searchResults() {
    if (_searching && _results == null) {
      return const LinearProgressIndicator(minHeight: 2);
    }
    if (_searchError != null) {
      return _ErrorCard(
        message: extractErrorMessage(_searchError!),
        onRetry: () => _runSearch(_search.text.trim()),
      );
    }
    final results = _results;
    if (results == null) return const SizedBox.shrink();
    return AdminSectionCard(
      title: '${results.totalElements} result${results.totalElements == 1 ? '' : 's'}',
      child: Column(
        children: [
          if (results.content.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: AdminSpacing.xl),
              child: Text('No matching platform records.'),
            ),
          for (final result in results.content)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: CircleAvatar(
                backgroundColor: AdminColors.primaryFaint,
                child: Icon(_iconFor(result.entityType), color: AdminColors.primaryDark),
              ),
              title: Text(result.title, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text([
                result.entityType,
                result.reference,
                result.subtitle,
                result.email,
                result.phone,
              ].whereType<String>().where((v) => v.isNotEmpty).join(' · ')),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => _openResult(result),
            ),
          if (results.hasMore)
            TextButton.icon(
              onPressed: _searching
                  ? null
                  : () => _runSearch(_search.text.trim(),
                      page: results.page + 1, append: true),
              icon: _searching
                  ? const SizedBox.square(
                      dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.expand_more_rounded),
              label: const Text('Load more'),
            ),
        ],
      ),
    );
  }

  Future<void> _openResult(PlatformSearchResult result) async {
    if (result.entityType == 'ORDER') {
      await Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => PlatformOrder360Screen(orderId: result.entityId),
      ));
      return;
    }
    if (const {'CUSTOMER', 'MERCHANT', 'SHOP'}.contains(result.entityType)) {
      await Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => PlatformEntity360Screen(result: result),
      ));
      return;
    }
    final resource = switch (result.entityType) {
      'WORKER' => ('workers', 'Workers', Icons.badge_outlined),
      'PRODUCT' => ('products', 'Products', Icons.inventory_2_outlined),
      _ => null,
    };
    if (resource != null) {
      await Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => PlatformResourceScreen(
          resource: resource.$1,
          title: resource.$2,
          icon: resource.$3,
          initialQuery: result.title,
        ),
      ));
    }
  }

  Widget _rangePicker() => Wrap(
        spacing: AdminSpacing.sm,
        runSpacing: AdminSpacing.sm,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          for (final option in const [(1, 'Today'), (2, 'Yesterday'), (7, '7 days'), (30, '30 days')])
            ChoiceChip(
              label: Text(option.$2),
              selected: _customRange == null && _rangeDays == option.$1,
              onSelected: (_) {
                if (option.$2 == 'Yesterday') {
                  final now = DateTime.now();
                  final yesterday = DateTime(now.year, now.month, now.day)
                      .subtract(const Duration(days: 1));
                  _customRange = DateTimeRange(start: yesterday, end: yesterday);
                } else {
                  _customRange = null;
                  _rangeDays = option.$1;
                }
                _reloadDashboard();
              },
            ),
          ActionChip(
            avatar: const Icon(Icons.date_range_outlined, size: 18),
            label: Text(_customRange == null ? 'Custom' : 'Custom selected'),
            onPressed: () async {
              final selected = await showDateRangePicker(
                context: context,
                firstDate: DateTime.now().subtract(const Duration(days: 730)),
                lastDate: DateTime.now(),
                initialDateRange: _range,
              );
              if (selected == null) return;
              _customRange = selected;
              _reloadDashboard();
            },
          ),
        ],
      );

  static IconData _iconFor(String type) => switch (type) {
        'CUSTOMER' => Icons.person_outline,
        'MERCHANT' => Icons.business_outlined,
        'SHOP' => Icons.storefront_outlined,
        'ORDER' => Icons.receipt_long_outlined,
        'WORKER' => Icons.badge_outlined,
        'PRODUCT' => Icons.inventory_2_outlined,
        _ => Icons.search_rounded,
      };
}

class _Dashboard extends StatelessWidget {
  const _Dashboard({required this.summary});

  final PlatformDashboardSummary summary;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Marketplace', style: AdminText.sectionTitle),
        const SizedBox(height: AdminSpacing.md),
        _grid([
          _Kpi('Merchants', '${summary.count('totalMerchants')}', Icons.business_outlined),
          _Kpi('Active merchants', '${summary.count('activeMerchants')}', Icons.verified_outlined),
          _Kpi('Pending merchants', '${summary.count('pendingMerchants')}', Icons.pending_actions_outlined),
          _Kpi('Suspended merchants', '${summary.count('suspendedMerchants')}', Icons.block_outlined),
          _Kpi('Shops', '${summary.count('totalShops')}', Icons.storefront_outlined),
          _Kpi('Accepting orders', '${summary.count('acceptingOrdersShops')}', Icons.shopping_bag_outlined),
          _Kpi('Paused shops', '${summary.count('pausedShops')}', Icons.pause_circle_outline),
          _Kpi('Closed shops', '${summary.count('closedShops')}', Icons.store_mall_directory_outlined),
          _Kpi('Suspended shops', '${summary.count('suspendedShops')}', Icons.block_outlined),
          _Kpi('Customers', '${summary.count('totalCustomers')}', Icons.people_outline),
          _Kpi('Active customer accounts', '${summary.count('activeCustomerAccounts')}', Icons.person_outline),
          _Kpi('New customers', '${summary.count('newCustomers')}', Icons.person_add_alt_outlined),
          _Kpi('Workers', '${summary.count('totalWorkers')}', Icons.badge_outlined),
          _Kpi('Active workers', '${summary.count('activeWorkers')}', Icons.delivery_dining_outlined),
        ]),
        const SizedBox(height: AdminSpacing.xl),
        Text('Orders', style: AdminText.sectionTitle),
        const SizedBox(height: AdminSpacing.md),
        _grid([
          for (final status in const [
            'PENDING_CONFIRMATION', 'CONFIRMED', 'PACKING', 'PACKED',
            'READY_TO_DISPATCH', 'OUT_FOR_DELIVERY', 'DELIVERED',
            'COMPLETED', 'CANCELLED', 'REJECTED', 'DELIVERY_FAILED',
            'RETURNED', 'REFUNDED'
          ])
            _Kpi(
              AdminStatusBadge.humanizeStatus(status),
              '${summary.orders(status)}',
              Icons.receipt_long_outlined,
            ),
        ]),
        const SizedBox(height: AdminSpacing.xl),
        Text('Finance', style: AdminText.sectionTitle),
        const SizedBox(height: AdminSpacing.sm),
        const Text(
          'GMV is completed merchandise value, not GP-STORE revenue. Delivery charges and refunds are shown separately.',
          style: AdminText.bodyMuted,
        ),
        const SizedBox(height: AdminSpacing.md),
        _grid([
          _Kpi('GMV', '₹${summary.money('gmv')}', Icons.show_chart_rounded),
          _Kpi('Completed checkout total', '₹${summary.money('completedSales')}', Icons.receipt_long_outlined),
          _Kpi('Merchant product sales', '₹${summary.money('merchantProductSales')}', Icons.store_outlined),
          _Kpi('Delivery charges', '₹${summary.money('deliveryCharges')}', Icons.local_shipping_outlined),
          _Kpi('Refunds', '₹${summary.money('refunds')}', Icons.currency_rupee_rounded),
          _Kpi('Commission ledger', '₹${summary.money('platformCommission')}', Icons.percent_rounded),
          _Kpi('Platform-fee ledger', '₹${summary.money('platformFees')}', Icons.account_balance_outlined),
          _Kpi('Cancellation fees', '₹${summary.money('cancellationFees')}', Icons.cancel_outlined),
          _Kpi('Adjustments', '₹${summary.money('platformAdjustments')}', Icons.tune_outlined),
        ]),
        const SizedBox(height: AdminSpacing.lg),
        AdminSectionCard(
          title: 'Recently active accounts',
          subtitle: summary.presenceAvailable
              ? 'Authenticated accounts with a request in the last ${summary.presenceWindowSeconds ?? 0} seconds'
              : 'Unavailable while the shared presence store cannot be reached',
          child: Text(
            summary.presenceAvailable
                ? '${summary.recentlyActiveAuthenticatedAccounts ?? 0}'
                : 'Unavailable',
            style: AdminText.metric,
          ),
        ),
      ],
    );
  }

  Widget _grid(List<_Kpi> items) => LayoutBuilder(builder: (context, constraints) {
        final columns = constraints.maxWidth >= 900 ? 4 : constraints.maxWidth >= 560 ? 3 : 2;
        const gap = AdminSpacing.md;
        final width = (constraints.maxWidth - gap * (columns - 1)) / columns;
        return Wrap(
          spacing: gap,
          runSpacing: gap,
          children: [
            for (final item in items)
              SizedBox(
                width: width,
                child: AdminKpiCard(icon: item.icon, label: item.label, value: item.value),
              ),
          ],
        );
      });
}

class _Kpi {
  const _Kpi(this.label, this.value, this.icon);
  final String label;
  final String value;
  final IconData icon;
}

class _ErrorCard extends StatelessWidget {
  const _ErrorCard({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => AdminSectionCard(
        child: Column(children: [
          Text(message, textAlign: TextAlign.center),
          const SizedBox(height: AdminSpacing.sm),
          OutlinedButton.icon(
            onPressed: onRetry,
            icon: const Icon(Icons.refresh_rounded),
            label: const Text('Retry'),
          ),
        ]),
      );
}
