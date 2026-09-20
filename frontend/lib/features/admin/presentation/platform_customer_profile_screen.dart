import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_format.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import '../domain/directory_models.dart';
import 'platform_providers.dart';

/// One customer, everything an operator legitimately needs, one request.
///
/// NO CREDENTIALS APPEAR HERE because none are sent: the backend projection
/// selects contact details, orders, payments and reviews, and never a
/// password, hash, OTP, activation code, token or payment credential. A test
/// asserts that over the whole response body. "Complete information" does not
/// mean the things that would let somebody become this person.
class PlatformCustomerProfileScreen extends ConsumerStatefulWidget {
  const PlatformCustomerProfileScreen({
    super.key,
    required this.customerId,
    required this.title,
  });

  final int customerId;
  final String title;

  @override
  ConsumerState<PlatformCustomerProfileScreen> createState() =>
      _PlatformCustomerProfileScreenState();
}

class _PlatformCustomerProfileScreenState
    extends ConsumerState<PlatformCustomerProfileScreen> {
  Map<String, dynamic>? _profile;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final profile = await ref
          .read(platformRepositoryProvider)
          .customerProfile(widget.customerId);
      if (!mounted) return;
      setState(() {
        _profile = profile;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = extractErrorMessage(e);
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.title, overflow: TextOverflow.ellipsis)),
      body: _loading
          ? const Center(child: CircularProgressIndicator(color: AdminColors.primary))
          : _error != null
              ? AdminErrorState(message: _error!, onRetry: hapticize(_load))
              : _body(),
    );
  }

  Widget _body() {
    final profile = _profile!;
    final core = _map(profile['core']);
    final identity = _map(core['identity']);
    final orders = _map(core['orders']);
    final finance = _map(core['finance']);
    final addresses = _list(profile['addresses']);
    final shops = _list(profile['shops']).map(ShopAffinity.fromJson).toList();
    final categories = _list(profile['categories']);
    final payments = _list(profile['payments']);
    final refunds = _list(profile['refunds']);
    final reviews = _list(profile['reviews']);
    final returns = _list(profile['returns']);
    final activity = CustomerActivity.fromJson(_map(profile['activity']));
    final recentOrders = _list(core['recentOrders']);

    return ListView(
      padding: const EdgeInsets.all(AdminSpacing.lg),
      children: [
        AdminSectionCard(
          title: (identity['name'] ?? '—') as String,
          subtitle: identity['customerRef'] as String?,
          child: Column(children: [
            _Row('Email', (identity['email'] ?? '—') as String),
            _Row('Phone', (identity['phone'] ?? '—') as String),
            _Row('Account', _yes(identity['active']) ? 'Active' : 'Inactive'),
            _Row('Verified', _yes(identity['verified']) ? 'Yes' : 'No'),
            _Row('Joined', _when(_time(identity['createdAt']))),
            _Row('Last active', _when(activity.lastSessionAt ?? activity.lastOrderAt)),
          ]),
        ),
        const SizedBox(height: AdminSpacing.lg),

        AdminSectionCard(
          title: 'Orders',
          child: Column(children: [
            _Row('Total', '${_num(orders['total']).toInt()}'),
            _Row('Completed', '${_num(orders['completed']).toInt()}'),
            _Row('Active', '${_num(orders['active']).toInt()}'),
            _Row('Cancelled', '${_num(orders['cancelled']).toInt()}'),
            _Row('Returned', '${_num(orders['returned']).toInt()}'),
            _Row('Refunded', '${_num(orders['refunded']).toInt()}'),
            const Divider(height: 18),
            _Row('Spent on products',
                AdminFormat.rupees(_money(finance['completedPurchaseValue']))),
            _Row('Refunded', AdminFormat.rupees(_money(finance['refunds']))),
            _Row('Cancellation charges',
                AdminFormat.rupees(_money(finance['cancellationFees']))),
          ]),
        ),
        const SizedBox(height: AdminSpacing.lg),

        _WhereTheyBuy(shops: shops, categories: categories),
        const SizedBox(height: AdminSpacing.lg),

        if (addresses.isNotEmpty) ...[
          AdminSectionCard(
            title: 'Delivery addresses',
            child: Column(children: [
              for (final address in addresses) _AddressRow(address: address),
            ]),
          ),
          const SizedBox(height: AdminSpacing.lg),
        ],

        // MULTI-SHOP CHECKOUT STAYS SEPARATE. Three shops is three orders and
        // three receipts; merging them into one line here would invent an
        // order that never existed.
        AdminSectionCard(
          title: 'Recent orders',
          subtitle: recentOrders.isEmpty ? 'None yet' : null,
          child: Column(children: [
            for (final order in recentOrders) _OrderRow(order: order),
          ]),
        ),
        const SizedBox(height: AdminSpacing.lg),

        if (payments.isNotEmpty) ...[
          AdminSectionCard(
            title: 'Payments',
            child: Column(children: [
              for (final payment in payments.take(10)) _PaymentRow(payment: payment),
            ]),
          ),
          const SizedBox(height: AdminSpacing.lg),
        ],

        if (refunds.isNotEmpty) ...[
          AdminSectionCard(
            title: 'Refunds',
            child: Column(children: [
              for (final refund in refunds.take(10))
                _Row('${refund['orderNumber'] ?? refund['orderId'] ?? '—'} · '
                        '${refund['status'] ?? '—'}',
                    AdminFormat.rupees(_money(refund['amount']))),
            ]),
          ),
          const SizedBox(height: AdminSpacing.lg),
        ],

        if (returns.isNotEmpty) ...[
          AdminSectionCard(
            title: 'Returns',
            child: Column(children: [
              for (final entry in returns.take(10))
                _Row('${entry['orderNumber'] ?? '—'} · ${entry['status'] ?? '—'}',
                    AdminFormat.rupees(_money(entry['refundAmount']))),
            ]),
          ),
          const SizedBox(height: AdminSpacing.lg),
        ],

        if (reviews.isNotEmpty) ...[
          AdminSectionCard(
            title: 'Reviews',
            child: Column(children: [
              for (final review in reviews.take(10)) _ReviewRow(review: review),
            ]),
          ),
          const SizedBox(height: AdminSpacing.lg),
        ],

        _Activity(activity: activity),
        const SizedBox(height: AdminSpacing.xxl),
      ],
    );
  }
}

/// §13, and the distinction it exists to protect.
class _WhereTheyBuy extends StatelessWidget {
  const _WhereTheyBuy({required this.shops, required this.categories});

  final List<ShopAffinity> shops;
  final List<Map<String, dynamic>> categories;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      title: 'Where they buy',
      subtitle: shops.isEmpty ? 'No orders yet' : 'Most used shops first',
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        for (final shop in shops.take(8))
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(children: [
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Flexible(
                      child: Text(shop.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontWeight: FontWeight.w600, fontSize: 13)),
                    ),
                    // THE BADGE IS THE POINT. It appears only when the
                    // customer explicitly saved this shop - never because
                    // they happen to buy here often.
                    if (shop.preferred) ...[
                      const SizedBox(width: 6),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: AdminColors.primary.withValues(alpha: 0.12),
                          borderRadius: BorderRadius.circular(20),
                        ),
                        child: const Text('Preferred',
                            style: TextStyle(
                                fontSize: 10,
                                fontWeight: FontWeight.w700,
                                color: AdminColors.primary)),
                      ),
                    ],
                  ]),
                  Text(shop.ordersLabel,
                      style: const TextStyle(
                          fontSize: 11.5, color: AdminColors.textSecondary)),
                ]),
              ),
              Text(AdminFormat.rupees(_money(shop.spent)),
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5)),
            ]),
          ),
        if (shops.isNotEmpty && categories.isNotEmpty) const Divider(height: 18),
        if (categories.isNotEmpty)
          Wrap(
            spacing: 6,
            runSpacing: 4,
            children: [
              for (final category in categories.take(8))
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: AdminColors.background,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                      '${category['categoryName'] ?? '—'} · '
                      '${_num(category['orders']).toInt()}',
                      style: const TextStyle(
                          fontSize: 11.5, color: AdminColors.textSecondary)),
                ),
            ],
          ),
        if (shops.isNotEmpty) ...[
          const SizedBox(height: 10),
          const Text(
            'A shop is marked Preferred only when the customer saved it. Buying '
            'somewhere often is not the same thing.',
            style: TextStyle(fontSize: 11, height: 1.35, color: AdminColors.textSecondary),
          ),
        ],
      ]),
    );
  }
}

class _Activity extends StatelessWidget {
  const _Activity({required this.activity});

  final CustomerActivity activity;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      title: 'App activity',
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _Row('Sessions', '${activity.sessions}'),
        _Row('Total time', activity.totalTimeLabel),
        _Row('Active days', '${activity.activeDays}'),
        _Row('First seen', _when(activity.firstSessionAt)),
        _Row('Last seen', _when(activity.lastSessionAt)),
        if (activity.note.isNotEmpty) ...[
          const SizedBox(height: 10),
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: AdminColors.background,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Icon(Icons.info_outline, size: 15, color: AdminColors.textSecondary),
              const SizedBox(width: 6),
              Expanded(
                child: Text(activity.note,
                    style: const TextStyle(
                        fontSize: 11.5, height: 1.35, color: AdminColors.textSecondary)),
              ),
            ]),
          ),
        ],
      ]),
    );
  }
}

class _AddressRow extends StatelessWidget {
  const _AddressRow({required this.address});

  final Map<String, dynamic> address;

  @override
  Widget build(BuildContext context) {
    final parts = [address['line'], address['area'], address['city'], address['pincode']]
        .where((e) => e != null && '$e'.trim().isNotEmpty)
        .join(', ');
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(_yes(address['isDefault']) ? Icons.star : Icons.place_outlined,
            size: 15, color: AdminColors.textSecondary),
        const SizedBox(width: 6),
        Expanded(
          child: Text(parts.isEmpty ? '—' : parts,
              style: const TextStyle(fontSize: 12.5)),
        ),
      ]),
    );
  }
}

class _OrderRow extends StatelessWidget {
  const _OrderRow({required this.order});

  final Map<String, dynamic> order;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text((order['orderNumber'] ?? '—') as String,
                style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 12.5)),
            Text(
              [order['shopName'], order['status'], _when(_time(order['orderedAt']))]
                  .where((e) => e != null && '$e'.isNotEmpty)
                  .join(' · '),
              style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary),
            ),
          ]),
        ),
        Text(AdminFormat.rupees(_money(order['total'])),
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5)),
      ]),
    );
  }
}

class _PaymentRow extends StatelessWidget {
  const _PaymentRow({required this.payment});

  final Map<String, dynamic> payment;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(
              [payment['orderNumber'], payment['method'], payment['status']]
                  .where((e) => e != null && '$e'.isNotEmpty)
                  .join(' · '),
              style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600),
            ),
            // A PROVIDER REFERENCE, NOT A CREDENTIAL. This is the handle an
            // operator quotes when chasing a stuck payment with the provider.
            if (payment['providerPaymentId'] != null)
              Text(payment['providerPaymentId'] as String,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary)),
          ]),
        ),
        Text(AdminFormat.rupees(_money(payment['amount'])),
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5)),
      ]),
    );
  }
}

class _ReviewRow extends StatelessWidget {
  const _ReviewRow({required this.review});

  final Map<String, dynamic> review;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Text((review['targetName'] ?? review['kind'] ?? '—') as String,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 12.5)),
          ),
          if (review['rating'] != null)
            Text('${_num(review['rating']).toInt()}★',
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
          if (_yes(review['reported'])) ...[
            const SizedBox(width: 6),
            const Icon(Icons.flag_outlined, size: 14, color: AdminColors.danger),
          ],
        ]),
        if (review['comment'] != null && '${review['comment']}'.isNotEmpty)
          Text(review['comment'] as String,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 11.5, color: AdminColors.textSecondary)),
      ]),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
        Expanded(
          child: Text(label,
              style: const TextStyle(fontSize: 12.5, color: AdminColors.textSecondary)),
        ),
        Flexible(
          child: Text(value,
              textAlign: TextAlign.right,
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ]),
    );
  }
}

Map<String, dynamic> _map(Object? raw) =>
    raw is Map ? Map<String, dynamic>.from(raw) : <String, dynamic>{};

List<Map<String, dynamic>> _list(Object? raw) => raw is List
    ? raw.whereType<Map>().map((e) => Map<String, dynamic>.from(e)).toList()
    : const [];

num _num(Object? raw) => raw is num ? raw : 0;

double _money(Object? raw) => raw is num ? raw.toDouble() : 0;

bool _yes(Object? raw) => raw is bool && raw;

DateTime? _time(Object? raw) =>
    raw is String && raw.isNotEmpty ? DateTime.tryParse(raw) : null;

/// Null becomes an em dash, never "null" and never today's date.
String _when(DateTime? value) {
  if (value == null) return '—';
  final local = value.toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(local.day)}/${two(local.month)}/${local.year} '
      '${two(local.hour)}:${two(local.minute)}';
}
