import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_format.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import '../domain/directory_models.dart';
import 'platform_providers.dart';

/// One merchant, everything an operator legitimately needs, one request.
///
/// REUSES THE BACKEND'S COMPOSITION rather than stitching several calls
/// together here: the profile endpoint returns the existing Merchant360 as
/// `core` plus the sections that were missing, so this screen never computes
/// a total the server already knows. A figure computed twice is a figure that
/// eventually disagrees with itself.
class PlatformMerchantProfileScreen extends ConsumerStatefulWidget {
  const PlatformMerchantProfileScreen({
    super.key,
    required this.merchantId,
    required this.title,
  });

  final int merchantId;
  final String title;

  @override
  ConsumerState<PlatformMerchantProfileScreen> createState() =>
      _PlatformMerchantProfileScreenState();
}

class _PlatformMerchantProfileScreenState
    extends ConsumerState<PlatformMerchantProfileScreen> {
  Map<String, dynamic>? _profile;
  String? _error;
  bool _loading = true;
  int _days = 30;

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
      final now = DateTime.now();
      final profile = await ref.read(platformRepositoryProvider).merchantProfile(
            widget.merchantId,
            from: _days == 0 ? DateTime(2020) : now.subtract(Duration(days: _days)),
            to: now,
          );
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
    final shops = _list(core['shops']);
    final commerce = _map(profile['commerce']);
    final workforce = _map(profile['workforce']);
    final reputation = _map(profile['reputation']);
    final activity = MerchantActivity.fromJson(_map(profile['activity']));
    final security = _list(profile['security'])
        .map((e) => AuditEntry.fromJson(e))
        .toList();

    return ListView(
      padding: const EdgeInsets.all(AdminSpacing.lg),
      children: [
        _Identity(identity: identity, shopCount: shops.length),
        const SizedBox(height: AdminSpacing.lg),

        // THE SAME FOUR-TILE SUMMARY THE CUSTOMER 360 OPENS WITH, because an
        // operator moving between the two screens should not have to relearn
        // where the headline numbers are. The tiles differ; the shape does not.
        _StatCards(core: core, shopCount: shops.length, reputation: reputation),
        const SizedBox(height: AdminSpacing.lg),

        _RangePicker(days: _days, onChanged: (d) { setState(() => _days = d); _load(); }),
        const SizedBox(height: AdminSpacing.lg),

        // MERCHANT-WIDE TOTALS FIRST, then the per-shop breakdown - §4 asked
        // for both, and a total is the question an operator opens this screen
        // with.
        AdminSectionCard(
          title: 'Money',
          subtitle: 'GMV is customer spend, not GP-STORE revenue',
          child: Column(children: [
            _Row('GMV', AdminFormat.rupeesExact(_money(core['gmv']))),
            _Row('Merchant product sales', AdminFormat.rupeesExact(_money(core['merchantProductSales']))),
            _Row('Delivery charges', AdminFormat.rupeesExact(_money(core['deliveryCharges']))),
            _Row('Refunds', AdminFormat.rupeesExact(_money(core['refunds']))),
            _Row('Platform commission', AdminFormat.rupeesExact(_money(core['platformCommission']))),
            _Row('Platform fees', AdminFormat.rupeesExact(_money(core['platformFees']))),
          ]),
        ),
        const SizedBox(height: AdminSpacing.lg),

        AdminSectionCard(
          title: 'Trading',
          subtitle: 'Across all three ways this merchant sells',
          child: Column(children: [
            _Row('Orders', '${_num(core['totalOrders']).toInt()}'),
            _Row('Completed', '${_num(core['completedOrders']).toInt()}'),
            _Row('Cancelled', '${_num(core['cancelledOrders']).toInt()}'),
            const Divider(height: 18),
            _Row('Listings', '${_num(commerce['activeListings']).toInt()}'),
            _Row('Out of stock', '${_num(commerce['outOfStockListings']).toInt()}'),
            _Row('Buy Online', '${_num(commerce['onlineListings']).toInt()}'),
            _Row('Visit to Buy', '${_num(commerce['visitToBuyListings']).toInt()}'),
            _Row('Service at Shop', '${_num(commerce['serviceListings']).toInt()}'),
            _Row('Active offers', '${_num(commerce['activeOffers']).toInt()}'),
          ]),
        ),
        const SizedBox(height: AdminSpacing.lg),

        AdminSectionCard(
          title: 'Shops',
          subtitle: shops.length == 1 ? '1 shop' : '${shops.length} shops',
          child: Column(
            children: [
              for (final shop in shops) _ShopRow(shop: shop),
            ],
          ),
        ),
        const SizedBox(height: AdminSpacing.lg),

        AdminSectionCard(
          title: 'Workers',
          child: Column(children: [
            _Row('Total', '${_num(workforce['total']).toInt()}'),
            _Row('Active', '${_num(workforce['active']).toInt()}'),
            _Row('Inactive', '${_num(workforce['inactive']).toInt()}'),
          ]),
        ),
        const SizedBox(height: AdminSpacing.lg),

        AdminSectionCard(
          title: 'Reputation',
          child: Column(children: [
            _Row('Rating', _rating(_num(reputation['averageRating']))),
            _Row('Last 90 days', _rating(_num(reputation['recentRating']))),
            _Row('Ratings', '${_num(reputation['ratingCount']).toInt()}'),
            _Row('Reported reviews', '${_num(reputation['reportedReviews']).toInt()}'),
            _Row('Returns requested', '${_num(reputation['returnsRequested']).toInt()}'),
            _Row('Returns approved', '${_num(reputation['returnsApproved']).toInt()}'),
            _Row('Refunds', '${_num(reputation['refundCount']).toInt()}'),
          ]),
        ),
        const SizedBox(height: AdminSpacing.lg),

        _Activity(activity: activity),
        const SizedBox(height: AdminSpacing.lg),

        AdminSectionCard(
          title: 'Security and admin actions',
          subtitle: security.isEmpty ? 'Nothing recorded' : null,
          child: Column(
            children: [for (final entry in security.take(20)) _AuditRow(entry: entry)],
          ),
        ),
        const SizedBox(height: AdminSpacing.xxl),
      ],
    );
  }

  static String _rating(num value) =>
      value <= 0 ? '—' : value.toStringAsFixed(1);
}

/// Who this merchant is, read at a glance.
///
/// INITIALS, NEVER A LOGO WE DO NOT HAVE. GP-STORE stores no merchant logo,
/// so there is nothing to draw and nothing is invented to fill the space -
/// the monogram is derived from the name on the account and is honest about
/// being exactly that.
class _Identity extends StatelessWidget {
  const _Identity({required this.identity, required this.shopCount});

  final Map<String, dynamic> identity;
  final int shopCount;

  @override
  Widget build(BuildContext context) {
    final name = ((identity['displayName'] ?? identity['legalName'] ?? '')
            as String)
        .trim();
    final legal = ((identity['legalName'] ?? '') as String).trim();
    final status = ((identity['status'] ?? '') as String).trim();
    final reason = ((identity['statusReason'] ?? '') as String).trim();

    return AdminSectionCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(
            width: 64,
            height: 64,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: AdminColors.primaryLight,
              borderRadius: BorderRadius.circular(AdminRadius.lg),
            ),
            child: Text(_monogram(name),
                style: const TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w700,
                    color: AdminColors.primaryDeep)),
          ),
          const SizedBox(width: AdminSpacing.lg),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(name.isEmpty ? 'Unnamed merchant' : name,
                  style: const TextStyle(
                      fontSize: 19,
                      fontWeight: FontWeight.w700,
                      color: AdminColors.textPrimary)),
              const SizedBox(height: 2),
              Text((identity['merchantRef'] ?? '') as String,
                  style: const TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: AdminColors.textSecondary)),
              const SizedBox(height: AdminSpacing.sm),
              Wrap(spacing: 6, runSpacing: 6, children: [
                if (status.isNotEmpty)
                  AdminStatusBadge(
                      label: _pretty(status), tone: _merchantTone(status)),
                if (_yes(identity['demo']))
                  const AdminStatusBadge(
                      label: 'Demo', tone: AdminStatusTone.warning),
                if ((identity['tier'] as String?) != null &&
                    '${identity['tier']}'.trim().isNotEmpty)
                  AdminStatusBadge(
                      label: _pretty('${identity['tier']}'),
                      tone: AdminStatusTone.neutral),
              ]),
            ]),
          ),
        ]),
        if (reason.isNotEmpty) ...[
          const SizedBox(height: AdminSpacing.md),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: AdminColors.neutralBg,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text('Status reason: $reason',
                style: const TextStyle(
                    fontSize: 11.5, height: 1.35, color: AdminColors.textSecondary)),
          ),
        ],
        const Divider(height: AdminSpacing.xl),
        if (legal.isNotEmpty && legal != name) _Row('Legal name', legal),
        _Row('Owner', (identity['ownerCustomerRef'] ?? '—') as String),
        _Row('Email', (identity['email'] ?? '—') as String),
        _Row('Phone', (identity['phone'] ?? '—') as String),
        _Row('Shops', '$shopCount'),
        _Row('Joined', _when(_time(identity['createdAt']))),
      ]),
    );
  }

  /// Up to two initials, taken with runes so a Devanagari or emoji first
  /// letter is not sliced into replacement boxes.
  static String _monogram(String name) {
    final words =
        name.split(RegExp(r'\s+')).where((w) => w.trim().isNotEmpty).toList();
    if (words.isEmpty) return '?';
    String first(String word) {
      final runes = word.runes;
      return runes.isEmpty ? '?' : String.fromCharCode(runes.first).toUpperCase();
    }
    return words.length == 1
        ? first(words.first)
        : first(words.first) + first(words.last);
  }

  static AdminStatusTone _merchantTone(String status) =>
      switch (status.toUpperCase()) {
        'ACTIVE' || 'LIVE' => AdminStatusTone.success,
        'SUSPENDED' || 'CLOSED' || 'REJECTED' => AdminStatusTone.danger,
        'PENDING' || 'ONBOARDING' || 'PENDING_VERIFICATION' =>
          AdminStatusTone.warning,
        _ => AdminStatusTone.neutral,
      };
}

/// The four numbers an operator opens a merchant for.
class _StatCards extends StatelessWidget {
  const _StatCards({
    required this.core,
    required this.shopCount,
    required this.reputation,
  });

  final Map<String, dynamic> core;
  final int shopCount;
  final Map<String, dynamic> reputation;

  @override
  Widget build(BuildContext context) {
    final rating = _num(reputation['averageRating']);
    final cards = <Widget>[
      AdminKpiCard(
        icon: Icons.payments_outlined,
        label: 'GMV',
        value: AdminFormat.rupeesCompact(_money(core['gmv'])),
      ),
      AdminKpiCard(
        icon: Icons.receipt_long_outlined,
        label: 'Orders',
        value: AdminFormat.count(_num(core['totalOrders']).toInt()),
      ),
      AdminKpiCard(
        icon: Icons.storefront_outlined,
        label: shopCount == 1 ? 'Shop' : 'Shops',
        value: AdminFormat.count(shopCount),
      ),
      AdminKpiCard(
        icon: Icons.star_outline,
        // AN UNRATED MERCHANT IS NOT A ZERO-STAR MERCHANT. A dash says
        // nobody has rated them; 0.0 would say every customer hated them.
        label: 'Rating',
        value: rating <= 0 ? '—' : rating.toStringAsFixed(1),
      ),
    ];
    return LayoutBuilder(builder: (context, constraints) {
      final columns = constraints.maxWidth >= 720 ? 4 : 2;
      const spacing = AdminSpacing.md;
      final width = (constraints.maxWidth - spacing * (columns - 1)) / columns;
      return Wrap(
        spacing: spacing,
        runSpacing: spacing,
        children: [
          for (final card in cards) SizedBox(width: width, child: card),
        ],
      );
    });
  }
}

bool _yes(Object? raw) => raw is bool && raw;

/// SCREAMING_SNAKE from the database into something a person reads.
String _pretty(String raw) {
  final words = raw.trim().replaceAll('_', ' ').toLowerCase();
  if (words.isEmpty) return raw;
  return words[0].toUpperCase() + words.substring(1);
}

/// §8's answer, drawn honestly.
///
/// When the platform cannot measure something it says so in words rather than
/// drawing a zero, because a zero on a dashboard reads as "this merchant did
/// nothing" and the truth is "GP-STORE did not look".
class _Activity extends StatelessWidget {
  const _Activity({required this.activity});

  final MerchantActivity activity;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      title: 'Activity',
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _Row('Last activity', _when(activity.lastActivityAt)),
        _Row('First order', _when(activity.firstOrderAt)),
        _Row('Last order', _when(activity.lastOrderAt)),
        _Row('Last listing edit', _when(activity.lastListingUpdateAt)),
        _Row('Active days in range', '${activity.activeDaysInWindow}'),
        if (!activity.sessionsMeasured && activity.note.isNotEmpty) ...[
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

class _ShopRow extends StatelessWidget {
  const _ShopRow({required this.shop});

  final Map<String, dynamic> shop;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Text((shop['name'] ?? shop['shopRef'] ?? '—') as String,
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13.5)),
          ),
          Text(AdminFormat.rupees(_money(shop['gmv'])),
              style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
        ]),
        const SizedBox(height: 2),
        Text(
          [
            shop['shopRef'],
            shop['city'],
            shop['status'],
            '${_num(shop['products']).toInt()} listings',
            '${_num(shop['workers']).toInt()} workers',
            '${_num(shop['orders']).toInt()} orders',
          ].where((e) => e != null && '$e'.isNotEmpty).join(' · '),
          style: const TextStyle(fontSize: 11.5, color: AdminColors.textSecondary),
        ),
      ]),
    );
  }
}

class _AuditRow extends StatelessWidget {
  const _AuditRow({required this.entry});

  final AuditEntry entry;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Text(entry.action ?? '—',
                style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 12.5)),
          ),
          Text(_when(entry.occurredAt),
              style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary)),
        ]),
        if (entry.transition != null)
          Text(entry.transition!,
              style: const TextStyle(fontSize: 11.5, color: AdminColors.textSecondary)),
        if (entry.actorEmail != null)
          Text('by ${entry.actorEmail}',
              style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary)),
        if (entry.reason != null && entry.reason!.isNotEmpty)
          Text(entry.reason!,
              style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary)),
      ]),
    );
  }
}

class _RangePicker extends StatelessWidget {
  const _RangePicker({required this.days, required this.onChanged});

  final int days;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    const options = {1: 'Today', 7: '7 days', 30: '30 days', 365: '1 year', 0: 'All'};
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          for (final entry in options.entries)
            Padding(
              padding: const EdgeInsets.only(right: 6),
              child: ChoiceChip(
                label: Text(entry.value, style: const TextStyle(fontSize: 12)),
                selected: days == entry.key,
                onSelected: (_) => onChanged(entry.key),
              ),
            ),
        ],
      ),
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
        Text(label, style: const TextStyle(fontSize: 12.5, color: AdminColors.textSecondary)),
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

/// A date an operator can read. Null becomes an em dash rather than "null"
/// or today's date - "we have no record of this" is a real answer and must
/// not be dressed up as one.
String _when(DateTime? value) {
  if (value == null) return '—';
  final local = value.toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(local.day)}/${two(local.month)}/${local.year} '
      '${two(local.hour)}:${two(local.minute)}';
}

DateTime? _time(Object? raw) =>
    raw is String && raw.isNotEmpty ? DateTime.tryParse(raw) : null;
