import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/api/error_messages.dart';
import '../domain/control_tower_models.dart';
import 'platform_providers.dart';
import 'platform_resource_screen.dart';

/// Authoritative platform money summary. The backend computes every amount;
/// this screen only selects a period and explains the categories.
class PlatformFinanceScreen extends ConsumerStatefulWidget {
  const PlatformFinanceScreen({super.key});

  @override
  ConsumerState<PlatformFinanceScreen> createState() =>
      _PlatformFinanceScreenState();
}

class _PlatformFinanceScreenState
    extends ConsumerState<PlatformFinanceScreen> {
  int _days = 1;
  bool _yesterday = false;
  DateTimeRange? _custom;
  int? _merchantId;
  int? _shopId;
  String? _orderStatus;
  String? _paymentStatus;
  String? _paymentMethod;
  late Future<PlatformDashboardSummary> _future;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  DateTimeRange get _range {
    if (_custom != null) return _custom!;
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    if (_yesterday) {
      final day = today.subtract(const Duration(days: 1));
      return DateTimeRange(start: day, end: day);
    }
    return DateTimeRange(
      start: today.subtract(Duration(days: _days - 1)),
      end: today,
    );
  }

  Future<PlatformDashboardSummary> _load() {
    final range = _range;
    return ref.read(platformRepositoryProvider).controlTowerDashboard(
          from: range.start,
          to: range.end,
          merchantId: _merchantId,
          shopId: _shopId,
          orderStatus: _orderStatus,
          paymentStatus: _paymentStatus,
          paymentMethod: _paymentMethod,
        );
  }

  void _reload() => setState(() => _future = _load());

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Finance')),
        backgroundColor: AdminColors.background,
        body: RefreshIndicator(
          onRefresh: () async => _reload(),
          child: ListView(
            padding: const EdgeInsets.all(AdminSpacing.lg),
            children: [
              _rangePicker(),
              if (_filterLabels.isNotEmpty) ...[
                const SizedBox(height: AdminSpacing.sm),
                Wrap(
                  spacing: AdminSpacing.sm,
                  runSpacing: AdminSpacing.sm,
                  children: [
                    for (final label in _filterLabels) Chip(label: Text(label)),
                    ActionChip(
                      label: const Text('Clear filters'),
                      avatar: const Icon(Icons.close_rounded, size: 17),
                      onPressed: _clearFilters,
                    ),
                  ],
                ),
              ],
              const SizedBox(height: AdminSpacing.md),
              const AdminSectionCard(
                title: 'Money definitions',
                child: Text(
                  'GMV is completed merchandise value after discounts and excludes delivery. '
                  'Completed checkout total includes delivery. Commission, platform fees, '
                  'refunds, cancellation fees and adjustments remain separate ledgers.',
                  style: AdminText.bodyMuted,
                ),
              ),
              const SizedBox(height: AdminSpacing.md),
              FutureBuilder<PlatformDashboardSummary>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(
                      child: CircularProgressIndicator(strokeWidth: 2),
                    );
                  }
                  if (snapshot.hasError) {
                    return AdminSectionCard(
                      child: Column(children: [
                        Text(extractErrorMessage(snapshot.error!)),
                        TextButton(
                          onPressed: _reload,
                          child: const Text('Retry'),
                        ),
                      ]),
                    );
                  }
                  return _money(snapshot.data!);
                },
              ),
            ],
          ),
        ),
      );

  Widget _rangePicker() => Wrap(
        spacing: AdminSpacing.sm,
        runSpacing: AdminSpacing.sm,
        children: [
          for (final option in const [
            (1, 'Today'),
            (0, 'Yesterday'),
            (7, '7 days'),
            (30, '30 days'),
          ])
            ChoiceChip(
              label: Text(option.$2),
              selected: _custom == null &&
                  (option.$1 == 0
                      ? _yesterday
                      : !_yesterday && _days == option.$1),
              onSelected: (_) {
                _custom = null;
                _yesterday = option.$1 == 0;
                if (!_yesterday) _days = option.$1;
                _reload();
              },
            ),
          ActionChip(
            avatar: const Icon(Icons.date_range_outlined, size: 18),
            label: Text(_custom == null ? 'Custom' : 'Custom selected'),
            onPressed: () async {
              final selected = await showDateRangePicker(
                context: context,
                firstDate: DateTime.now().subtract(const Duration(days: 730)),
                lastDate: DateTime.now(),
                initialDateRange: _range,
              );
              if (selected == null) return;
              _custom = selected;
              _yesterday = false;
              _reload();
            },
          ),
          ActionChip(
            avatar: const Icon(Icons.tune_rounded, size: 18),
            label: Text(_filterLabels.isEmpty
                ? 'Filters'
                : 'Filters (${_filterLabels.length})'),
            onPressed: _showFilters,
          ),
        ],
      );

  List<String> get _filterLabels => [
        if (_merchantId != null) 'Merchant: $_merchantId',
        if (_shopId != null) 'Shop: $_shopId',
        if (_orderStatus != null) 'Order: $_orderStatus',
        if (_paymentStatus != null) 'Payment: $_paymentStatus',
        if (_paymentMethod != null) 'Method: $_paymentMethod',
      ];

  void _clearFilters() {
    _merchantId = null;
    _shopId = null;
    _orderStatus = null;
    _paymentStatus = null;
    _paymentMethod = null;
    _reload();
  }

  Future<void> _showFilters() async {
    final merchant = TextEditingController(text: _merchantId?.toString());
    final shop = TextEditingController(text: _shopId?.toString());
    final order = TextEditingController(text: _orderStatus);
    final payment = TextEditingController(text: _paymentStatus);
    final method = TextEditingController(text: _paymentMethod);
    final apply = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Finance filters'),
        content: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            _field(merchant, 'Merchant ID', numeric: true),
            _field(shop, 'Shop ID', numeric: true),
            _field(order, 'Order status'),
            _field(payment, 'Payment status'),
            _field(method, 'Payment method'),
          ]),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Apply'),
          ),
        ],
      ),
    );
    if (apply == true && mounted) {
      _merchantId = _id(merchant.text);
      _shopId = _id(shop.text);
      _orderStatus = _text(order.text);
      _paymentStatus = _text(payment.text);
      _paymentMethod = _text(method.text);
      _reload();
    }
    for (final controller in [merchant, shop, order, payment, method]) {
      controller.dispose();
    }
  }

  Widget _field(TextEditingController controller, String label,
          {bool numeric = false}) =>
      Padding(
        padding: const EdgeInsets.only(bottom: AdminSpacing.sm),
        child: TextField(
          controller: controller,
          keyboardType: numeric ? TextInputType.number : TextInputType.text,
          textCapitalization:
              numeric ? TextCapitalization.none : TextCapitalization.characters,
          decoration: InputDecoration(
            labelText: label,
            border: const OutlineInputBorder(),
          ),
        ),
      );

  static int? _id(String value) {
    final parsed = int.tryParse(value.trim());
    return parsed != null && parsed > 0 ? parsed : null;
  }

  static String? _text(String value) {
    final trimmed = value.trim();
    return trimmed.isEmpty ? null : trimmed;
  }

  Widget _money(PlatformDashboardSummary summary) => Column(
        children: [
          _moneyTile('Merchandise GMV', summary.money('gmv'),
              'Completed product value; delivery excluded.', 'orders'),
          _moneyTile('Completed checkout total', summary.money('completedSales'),
              'Completed merchandise plus delivery.', 'orders'),
          _moneyTile('Merchant product sales', summary.money('merchantProductSales'),
              'Product sales before refunds.', 'orders'),
          _moneyTile('Delivery charges', summary.money('deliveryCharges'),
              'Not part of GP-STORE commission.', 'orders'),
          _moneyTile('Refunds settled', summary.money('refunds'),
              'Money confirmed returned during this period.', 'refunds'),
          _moneyTile('Cancellation fees', summary.money('cancellationFees'),
              'Recorded separately from merchandise.', 'orders'),
          _moneyTile('Commission ledger', summary.money('platformCommission'),
              'Authoritative commission ledger entries.', null),
          _moneyTile('Platform-fee ledger', summary.money('platformFees'),
              'Authoritative platform-fee ledger entries.', null),
          _moneyTile('Adjustments', summary.money('platformAdjustments'),
              'Explicit ledger adjustments and intervention recovery.', null),
        ],
      );

  Widget _moneyTile(
    String label,
    String amount,
    String definition,
    String? drill,
  ) =>
      Padding(
        padding: const EdgeInsets.only(bottom: AdminSpacing.sm),
        child: Card(
          margin: EdgeInsets.zero,
          child: ListTile(
            title: Text(label),
            subtitle: Text(definition),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('₹$amount', style: AdminText.sectionTitle),
                if (drill != null) const Icon(Icons.chevron_right_rounded),
              ],
            ),
            onTap: drill == null
                ? null
                : () => Navigator.of(context).push(MaterialPageRoute<void>(
                      builder: (_) => PlatformResourceScreen(
                        resource: drill,
                        title: drill == 'refunds' ? 'Refunds' : 'Orders',
                        icon: drill == 'refunds'
                            ? Icons.currency_rupee_outlined
                            : Icons.receipt_long_outlined,
                        initialStatus:
                            drill == 'orders' ? _orderStatus : null,
                        initialMerchantId: _merchantId,
                        initialShopId: _shopId,
                        initialPaymentStatus: _paymentStatus,
                        initialPaymentMethod: _paymentMethod,
                        initialDateRange: _range,
                      ),
                    )),
          ),
        ),
      );
}
