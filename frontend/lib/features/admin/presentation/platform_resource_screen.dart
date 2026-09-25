import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/api/error_messages.dart';
import '../domain/control_tower_models.dart';
import 'platform_providers.dart';
import 'platform_entity_360_screen.dart';
import 'admin_worker_profile_screen.dart';

/// Reusable paged reader for platform operational projections. It contains no
/// write controls; sensitive actions continue through their established,
/// reasoned workflows.
class PlatformResourceScreen extends ConsumerStatefulWidget {
  const PlatformResourceScreen({
    super.key,
    required this.resource,
    required this.title,
    required this.icon,
    this.initialQuery = '',
    this.initialStatus,
    this.initialMerchantId,
    this.initialShopId,
    this.initialPaymentStatus,
    this.initialPaymentMethod,
    this.initialDateRange,
  });

  final String resource;
  final String title;
  final IconData icon;
  final String initialQuery;
  final String? initialStatus;
  final int? initialMerchantId;
  final int? initialShopId;
  final String? initialPaymentStatus;
  final String? initialPaymentMethod;
  final DateTimeRange? initialDateRange;

  @override
  ConsumerState<PlatformResourceScreen> createState() => _PlatformResourceScreenState();
}

class _PlatformResourceScreenState extends ConsumerState<PlatformResourceScreen> {
  final _query = TextEditingController();
  Timer? _debounce;
  PlatformResourcePage? _page;
  Object? _error;
  bool _loading = false;
  String? _status;
  String? _paymentStatus;
  String? _paymentMethod;
  String? _category;
  String? _stockStatus;
  int? _merchantId;
  int? _shopId;
  int? _customerId;
  int? _workerId;
  DateTimeRange? _dateRange;

  @override
  void initState() {
    super.initState();
    _query.text = widget.initialQuery;
    _status = widget.initialStatus;
    _merchantId = widget.initialMerchantId;
    _shopId = widget.initialShopId;
    _paymentStatus = widget.initialPaymentStatus;
    _paymentMethod = widget.initialPaymentMethod;
    _dateRange = widget.initialDateRange;
    _load();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _query.dispose();
    super.dispose();
  }

  Future<void> _load({int page = 0, bool append = false}) async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final next = await ref.read(platformRepositoryProvider).controlTowerResource(
            resource: widget.resource,
            query: _query.text,
            page: page,
            status: _status,
            merchantId: _merchantId,
            shopId: _shopId,
            customerId: _customerId,
            workerId: _workerId,
            paymentStatus: _paymentStatus,
            paymentMethod: _paymentMethod,
            category: _category,
            stockStatus: _stockStatus,
            from: _dateRange?.start,
            to: _dateRange?.end,
          );
      if (!mounted) return;
      setState(() {
        if (append && _page != null) {
          _page = PlatformResourcePage(
            content: [..._page!.content, ...next.content],
            page: next.page,
            totalPages: next.totalPages,
            totalElements: next.totalElements,
          );
        } else {
          _page = next;
        }
      });
    } catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: AdminColors.background,
        appBar: AppBar(title: Text(widget.title)),
        body: RefreshIndicator(
          onRefresh: _load,
          child: ListView(
            padding: const EdgeInsets.all(AdminSpacing.lg),
            children: [
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _query,
                      onChanged: (_) {
                        _debounce?.cancel();
                        _debounce = Timer(const Duration(milliseconds: 350), _load);
                      },
                      decoration: InputDecoration(
                        hintText: 'Search ${widget.title.toLowerCase()}',
                        prefixIcon: const Icon(Icons.search_rounded),
                        filled: true,
                        fillColor: AdminColors.surface,
                        border: const OutlineInputBorder(
                          borderRadius: AdminRadius.control,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: AdminSpacing.sm),
                  IconButton.filledTonal(
                    tooltip: 'Structured filters',
                    onPressed: _showFilters,
                    icon: Badge(
                      isLabelVisible: _filterCount > 0,
                      label: Text('$_filterCount'),
                      child: const Icon(Icons.tune_rounded),
                    ),
                  ),
                ],
              ),
              if (_filterCount > 0) ...[
                const SizedBox(height: AdminSpacing.sm),
                Wrap(
                  spacing: AdminSpacing.sm,
                  runSpacing: AdminSpacing.sm,
                  children: [
                    for (final label in _filterLabels)
                      Chip(label: Text(label)),
                    ActionChip(
                      label: const Text('Clear filters'),
                      avatar: const Icon(Icons.close_rounded, size: 17),
                      onPressed: _clearFilters,
                    ),
                  ],
                ),
              ],
              const SizedBox(height: AdminSpacing.lg),
              if (_loading && _page == null)
                const Center(child: CircularProgressIndicator(strokeWidth: 2))
              else if (_error != null)
                AdminSectionCard(
                  child: Column(children: [
                    Text(extractErrorMessage(_error!)),
                    TextButton(onPressed: _load, child: const Text('Retry')),
                  ]),
                )
              else
                _list(),
            ],
          ),
        ),
      );

  int get _filterCount => [
        _status,
        _paymentStatus,
        _paymentMethod,
        _category,
        _stockStatus,
        _merchantId,
        _shopId,
        _customerId,
        _workerId,
        _dateRange,
      ].where((value) => value != null).length;

  List<String> get _filterLabels => [
        if (_status != null) 'Status: $_status',
        if (_paymentStatus != null) 'Payment: $_paymentStatus',
        if (_paymentMethod != null) 'Method: $_paymentMethod',
        if (_category != null) 'Category: $_category',
        if (_stockStatus != null) 'Stock: $_stockStatus',
        if (_merchantId != null) 'Merchant: $_merchantId',
        if (_shopId != null) 'Shop: $_shopId',
        if (_customerId != null) 'Customer: $_customerId',
        if (_workerId != null) 'Worker: $_workerId',
        if (_dateRange != null)
          '${_shortDate(_dateRange!.start)} – ${_shortDate(_dateRange!.end)}',
      ];

  void _clearFilters() {
    setState(() {
      _status = null;
      _paymentStatus = null;
      _paymentMethod = null;
      _category = null;
      _stockStatus = null;
      _merchantId = null;
      _shopId = null;
      _customerId = null;
      _workerId = null;
      _dateRange = null;
    });
    _load();
  }

  Future<void> _showFilters() async {
    final status = TextEditingController(text: _status);
    final paymentStatus = TextEditingController(text: _paymentStatus);
    final paymentMethod = TextEditingController(text: _paymentMethod);
    final category = TextEditingController(text: _category);
    final stockStatus = TextEditingController(text: _stockStatus);
    final merchant = TextEditingController(text: _merchantId?.toString());
    final shop = TextEditingController(text: _shopId?.toString());
    final customer = TextEditingController(text: _customerId?.toString());
    final worker = TextEditingController(text: _workerId?.toString());
    var selectedRange = _dateRange;

    final apply = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text('Filter ${widget.title}'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_supportsStatus)
                  _filterField(status, 'Status', 'e.g. DELIVERED'),
                if (_supportsPayment) ...[
                  _filterField(paymentStatus, 'Payment status', 'e.g. SUCCESS'),
                  _filterField(paymentMethod, 'Payment method', 'e.g. ONLINE'),
                ],
                if (widget.resource == 'products') ...[
                  _filterField(category, 'Category', 'Exact category name'),
                  _filterField(
                      stockStatus, 'Stock status', 'IN_STOCK or OUT_OF_STOCK'),
                ],
                if (_supportsMerchant)
                  _filterField(merchant, 'Merchant ID', 'Numeric ID', numeric: true),
                if (_supportsShop)
                  _filterField(shop, 'Shop ID', 'Numeric ID', numeric: true),
                if (_supportsCustomer)
                  _filterField(customer, 'Customer ID', 'Numeric ID', numeric: true),
                if (_supportsWorker)
                  _filterField(worker, 'Worker ID', 'Numeric ID', numeric: true),
                if (_supportsDate)
                  Align(
                    alignment: Alignment.centerLeft,
                    child: OutlinedButton.icon(
                      icon: const Icon(Icons.date_range_outlined),
                      label: Text(selectedRange == null
                          ? 'Choose date range'
                          : '${_shortDate(selectedRange!.start)} – ${_shortDate(selectedRange!.end)}'),
                      onPressed: () async {
                        final picked = await showDateRangePicker(
                          context: dialogContext,
                          firstDate: DateTime.now().subtract(const Duration(days: 730)),
                          lastDate: DateTime.now(),
                          initialDateRange: selectedRange,
                        );
                        if (picked != null) {
                          setDialogState(() => selectedRange = picked);
                        }
                      },
                    ),
                  ),
              ],
            ),
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
      ),
    );

    if (apply == true && mounted) {
      setState(() {
        _status = _blank(status.text);
        _paymentStatus = _blank(paymentStatus.text);
        _paymentMethod = _blank(paymentMethod.text);
        _category = _blank(category.text);
        _stockStatus = _blank(stockStatus.text);
        _merchantId = _positiveInt(merchant.text);
        _shopId = _positiveInt(shop.text);
        _customerId = _positiveInt(customer.text);
        _workerId = _positiveInt(worker.text);
        _dateRange = selectedRange;
      });
      _load();
    }
    for (final controller in [
      status,
      paymentStatus,
      paymentMethod,
      category,
      stockStatus,
      merchant,
      shop,
      customer,
      worker,
    ]) {
      controller.dispose();
    }
  }

  Widget _filterField(
    TextEditingController controller,
    String label,
    String hint, {
    bool numeric = false,
  }) =>
      Padding(
        padding: const EdgeInsets.only(bottom: AdminSpacing.sm),
        child: TextField(
          controller: controller,
          keyboardType: numeric ? TextInputType.number : TextInputType.text,
          textCapitalization:
              numeric ? TextCapitalization.none : TextCapitalization.characters,
          decoration: InputDecoration(
            labelText: label,
            hintText: hint,
            border: const OutlineInputBorder(),
          ),
        ),
      );

  bool get _supportsStatus => const {
        'customers', 'merchants', 'shops', 'orders', 'workers', 'payments',
        'refunds', 'returns'
      }.contains(widget.resource);
  bool get _supportsPayment =>
      const {'orders', 'payments', 'refunds'}.contains(widget.resource);
  bool get _supportsMerchant => const {
        'merchants', 'shops', 'orders', 'workers', 'products', 'payments',
        'refunds', 'shop-reviews', 'audit', 'security'
      }.contains(widget.resource);
  bool get _supportsShop => const {
        'shops', 'orders', 'workers', 'products', 'payments', 'refunds',
        'returns', 'shop-reviews', 'audit', 'security'
      }.contains(widget.resource);
  bool get _supportsCustomer => const {
        'customers', 'orders', 'payments', 'refunds', 'returns', 'reviews',
        'shop-reviews'
      }.contains(widget.resource);
  bool get _supportsWorker => const {'workers', 'orders'}.contains(widget.resource);
  bool get _supportsDate => !const {'workers', 'products'}.contains(widget.resource);

  static String? _blank(String value) {
    final trimmed = value.trim();
    return trimmed.isEmpty ? null : trimmed;
  }

  static int? _positiveInt(String value) {
    final parsed = int.tryParse(value.trim());
    return parsed != null && parsed > 0 ? parsed : null;
  }

  static String _shortDate(DateTime value) =>
      '${value.day.toString().padLeft(2, '0')}/'
      '${value.month.toString().padLeft(2, '0')}/${value.year}';

  Widget _list() {
    final page = _page;
    if (page == null || page.content.isEmpty) {
      return const AdminSectionCard(child: Text('No matching records.'));
    }
    return AdminSectionCard(
      title: '${page.totalElements} ${widget.title.toLowerCase()}',
      child: Column(children: [
        for (final row in page.content)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: CircleAvatar(
              backgroundColor: AdminColors.primaryFaint,
              child: Icon(widget.icon, color: AdminColors.primaryDark),
            ),
            title: Text(_title(row), maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(_subtitle(row), maxLines: 3, overflow: TextOverflow.ellipsis),
            trailing: const {'orders', 'customers', 'merchants', 'shops', 'workers'}
                    .contains(widget.resource)
                ? const Icon(Icons.chevron_right_rounded)
                : null,
            onTap: row['id'] is num ? () => _open(row) : null,
          ),
        if (page.hasMore)
          TextButton.icon(
            onPressed: _loading ? null : () => _load(page: page.page + 1, append: true),
            icon: const Icon(Icons.expand_more_rounded),
            label: const Text('Load more'),
          ),
      ]),
    );
  }

  void _open(Map<String, dynamic> row) {
    final id = (row['id'] as num).toInt();
    if (widget.resource == 'workers') {
      Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => AdminWorkerProfileScreen(workerId: id, platformScope: true),
      ));
      return;
    }
    if (widget.resource == 'orders') {
      Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => PlatformOrder360Screen(orderId: id),
      ));
      return;
    }
    final type = switch (widget.resource) {
      'customers' => 'CUSTOMER',
      'merchants' => 'MERCHANT',
      'shops' => 'SHOP',
      _ => null,
    };
    if (type == null) return;
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => PlatformEntity360Screen(
        result: PlatformSearchResult(
          entityType: type,
          entityId: id,
          title: row['name']?.toString() ?? '$type $id',
          reference: '${type[0]}-$id',
        ),
      ),
    ));
  }

  String _title(Map<String, dynamic> row) {
    final candidates = [
      row['orderNumber'], row['product'], row['name'], row['refundReference'],
      row['action'], row['reviewType'], row['transactionReference'],
    ].where((value) => value != null && value.toString().isNotEmpty).toList();
    return candidates.isEmpty
        ? '${widget.title} ${row['id'] ?? ''}'
        : candidates.first.toString();
  }

  String _subtitle(Map<String, dynamic> row) => row.entries
      .where((entry) => entry.value != null &&
          !const {'id', 'orderNumber', 'product', 'name'}.contains(entry.key) &&
          !(entry.key == 'maskedEmail' && row.containsKey('email')) &&
          !(entry.key == 'maskedPhone' && row.containsKey('phone')))
      .take(5)
      .map((entry) => '${_label(entry.key)}: ${entry.value}')
      .join(' · ');

  static String _label(String value) {
    if (value == 'maskedEmail') return 'Email';
    if (value == 'maskedPhone') return 'Phone';
    return value
        .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]} ${m[2]}')
        .split(' ')
        .map((part) => part.isEmpty
            ? part
            : '${part[0].toUpperCase()}${part.substring(1)}')
        .join(' ');
  }
}

class PlatformOrder360Screen extends ConsumerStatefulWidget {
  const PlatformOrder360Screen({super.key, required this.orderId});
  final int orderId;

  @override
  ConsumerState<PlatformOrder360Screen> createState() => _PlatformOrder360ScreenState();
}

class _PlatformOrder360ScreenState extends ConsumerState<PlatformOrder360Screen> {
  late Future<Map<String, dynamic>> _future;

  @override
  void initState() {
    super.initState();
    _future = ref.read(platformRepositoryProvider).controlTowerOrder(widget.orderId);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text('Order #${widget.orderId}')),
        backgroundColor: AdminColors.background,
        body: FutureBuilder<Map<String, dynamic>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator(strokeWidth: 2));
            }
            if (snapshot.hasError) {
              return Center(child: Text(extractErrorMessage(snapshot.error!)));
            }
            final data = snapshot.data ?? const <String, dynamic>{};
            return ListView(
              padding: const EdgeInsets.all(AdminSpacing.lg),
              children: [
                AdminSectionCard(
                  title: data['orderNumber']?.toString() ?? 'Order',
                  child: _MapFacts(data, excludeCollections: true),
                ),
                for (final key in const ['items', 'payment', 'timeline'])
                  if (data[key] != null) ...[
                    const SizedBox(height: AdminSpacing.md),
                    AdminSectionCard(
                      title: PlatformResourceScreenStateLabel.label(key),
                      child: _CollectionFacts(value: data[key]),
                    ),
                  ],
              ],
            );
          },
        ),
      );
}

class _MapFacts extends StatelessWidget {
  const _MapFacts(this.data, {this.excludeCollections = false});
  final Map<String, dynamic> data;
  final bool excludeCollections;

  @override
  Widget build(BuildContext context) => Column(
        children: [
          for (final entry in data.entries)
            if (entry.value != null &&
                (!excludeCollections || (entry.value is! List && entry.value is! Map)))
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  SizedBox(
                    width: 140,
                    child: Text(PlatformResourceScreenStateLabel.label(entry.key),
                        style: AdminText.caption),
                  ),
                  Expanded(child: SelectableText(entry.value.toString())),
                ]),
              ),
        ],
      );
}

class _CollectionFacts extends StatelessWidget {
  const _CollectionFacts({required this.value});
  final dynamic value;

  @override
  Widget build(BuildContext context) {
    if (value is Map) return _MapFacts(Map<String, dynamic>.from(value as Map));
    if (value is List) {
      if ((value as List).isEmpty) return const Text('No recorded events.');
      return Column(
        children: [
          for (final item in value as List)
            if (item is Map)
              Padding(
                padding: const EdgeInsets.only(bottom: AdminSpacing.md),
                child: _MapFacts(Map<String, dynamic>.from(item)),
              ),
        ],
      );
    }
    return Text(value.toString());
  }
}

class PlatformResourceScreenStateLabel {
  static String label(String value) => value
      .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]} ${m[2]}')
      .replaceAll('_', ' ')
      .split(' ')
      .where((part) => part.isNotEmpty)
      .map((part) => '${part[0].toUpperCase()}${part.substring(1)}')
      .join(' ');
}
