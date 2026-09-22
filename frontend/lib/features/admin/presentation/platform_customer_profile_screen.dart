import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_format.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/images/gp_network_image.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import '../domain/directory_models.dart';
import 'platform_providers.dart';

/// One customer, as a profile an operator reads - not a dump of the row.
///
/// <h2>What changed and why</h2>
///
/// This screen used to be label-and-value lines all the way down, which is
/// the shape of the database rather than the shape of the question. Somebody
/// opening it is answering something specific - is this person real, are they
/// buying, did they get their money back, should they still be allowed in -
/// and each of those now has a place on the page instead of being assembled
/// out of eleven rows.
///
/// NO CREDENTIALS APPEAR HERE because none are sent: the backend projection
/// selects contact details, orders, payments and reviews, and never a
/// password, hash, OTP, activation code, token or payment credential. A test
/// asserts that over the whole response body. "Complete information" does not
/// mean the things that would let somebody become this person.
///
/// NOTHING ON THIS PAGE IS INVENTED. Where the customer has no photo they get
/// initials, not a stock face. Where they have saved no address it says so,
/// rather than hiding the section and leaving the operator to wonder whether
/// it failed to load. Every figure comes from the response; there is no
/// sample data and no placeholder chart.
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
      backgroundColor: AdminColors.background,
      appBar: AppBar(title: Text(widget.title, overflow: TextOverflow.ellipsis)),
      body: _loading
          ? const Center(child: CircularProgressIndicator(color: AdminColors.primary))
          : _error != null
              ? AdminErrorState(message: _error!, onRetry: hapticize(_load))
              : RefreshIndicator(
                  color: AdminColors.primary,
                  onRefresh: _load,
                  child: _body(),
                ),
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
    final security = _list(profile['security']);
    final activity = CustomerActivity.fromJson(_map(profile['activity']));

    return ListView(
      padding: const EdgeInsets.all(AdminSpacing.lg),
      children: [
        _ProfileHeader(identity: identity, activity: activity),
        const SizedBox(height: AdminSpacing.lg),

        _ContactCard(
          identity: identity,
          addresses: addresses,
        ),
        const SizedBox(height: AdminSpacing.lg),

        _StatCards(orders: orders, finance: finance, activity: activity),
        const SizedBox(height: AdminSpacing.lg),

        _FinanceCard(finance: finance, orders: orders),
        const SizedBox(height: AdminSpacing.lg),

        _WhereTheyBuy(shops: shops, categories: categories),
        const SizedBox(height: AdminSpacing.lg),

        // PAGED, NOT THE FIRST TWENTY. A customer two years in has hundreds
        // of orders and the question being asked is often about an old one.
        _OrderHistory(customerId: widget.customerId),
        const SizedBox(height: AdminSpacing.lg),

        _PaymentsCard(payments: payments, refunds: refunds),
        const SizedBox(height: AdminSpacing.lg),

        _ReviewsAndComplaints(reviews: reviews, returns: returns),
        const SizedBox(height: AdminSpacing.lg),

        _Activity(activity: activity),
        const SizedBox(height: AdminSpacing.lg),

        _SecurityCard(
          identity: identity,
          security: security,
          onChangeStatus: _changeStatus,
        ),
        const SizedBox(height: AdminSpacing.xxl),
      ],
    );
  }

  /// Bar this customer, or let them back in.
  Future<void> _changeStatus({required bool active}) async {
    final reason = await _askForReason(
      context,
      title: active ? 'Restore this account' : 'Bar this account',
      body: active
          ? 'They will be able to sign in again. They will have to log in '
              'fresh - old sessions are not restored.'
          : 'They are signed out of every device immediately and cannot sign '
              'in again until this is undone. Orders already placed are not '
              'touched.',
      confirmLabel: active ? 'Restore' : 'Bar the account',
      danger: !active,
    );
    if (reason == null || !mounted) return;
    try {
      await ref.read(platformRepositoryProvider).setCustomerActive(
            customerId: widget.customerId,
            active: active,
            reason: reason,
          );
      if (!mounted) return;
      _say(active ? 'Account restored.' : 'Account barred.');
      // RELOADED RATHER THAN PATCHED IN PLACE. The audit trail on this page
      // now has a new row in it, and the status shown must be the server's
      // answer, not the one the button assumed.
      await _load();
    } catch (e) {
      if (!mounted) return;
      _say(extractErrorMessage(e));
    }
  }

  void _say(String message) {
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(message)));
  }
}

/// A confirmation that will not proceed without a reason.
///
/// ONE DIALOG FOR EVERY CONSEQUENTIAL ACT on this screen. The reason field
/// is the whole point: the server rejects a blank one, so a dialog that let
/// the operator press through would only produce a failed request and a
/// confusing error.
Future<String?> _askForReason(
  BuildContext context, {
  required String title,
  required String body,
  required String confirmLabel,
  required bool danger,
}) {
  return showDialog<String>(
    context: context,
    builder: (dialogContext) => _ReasonDialog(
      title: title,
      body: body,
      confirmLabel: confirmLabel,
      danger: danger,
    ),
  );
}

/// A StatefulWidget rather than a StatefulBuilder with a controller closed
/// over, and that is not a style preference.
///
/// The controller has to outlive the pop: the route animates out with the
/// TextField still mounted, so disposing the controller the moment
/// showDialog's future completes tears it out from under a live widget and
/// the framework throws during the next layout. Owning it here means it is
/// disposed exactly when the field that uses it goes away.
class _ReasonDialog extends StatefulWidget {
  const _ReasonDialog({
    required this.title,
    required this.body,
    required this.confirmLabel,
    required this.danger,
  });

  final String title;
  final String body;
  final String confirmLabel;
  final bool danger;

  @override
  State<_ReasonDialog> createState() => _ReasonDialogState();
}

class _ReasonDialogState extends State<_ReasonDialog> {
  final _controller = TextEditingController();

  /// Five characters, the same floor the server enforces. A dialog that let
  /// the operator press through on a blank reason would only produce a
  /// rejected request and an error they cannot act on.
  static const _minimumReason = 5;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final reason = _controller.text.trim();
    final valid = reason.length >= _minimumReason;
    return AlertDialog(
      backgroundColor: AdminColors.surface,
      // SCROLLABLE, because on a short phone in landscape with the keyboard
      // up there is not room for the explanation and the field at once, and
      // an un-scrollable dialog would clip the button.
      scrollable: true,
      title: Text(widget.title, style: const TextStyle(fontSize: 16)),
      content: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(widget.body,
              style: const TextStyle(
                  fontSize: 13, height: 1.4, color: AdminColors.textSecondary)),
          const SizedBox(height: AdminSpacing.lg),
          TextField(
            controller: _controller,
            autofocus: true,
            maxLength: 500,
            minLines: 2,
            maxLines: 3,
            onChanged: (_) => setState(() {}),
            decoration: const InputDecoration(
              labelText: 'Reason',
              hintText: 'Ticket number, or what you are investigating',
              border: OutlineInputBorder(),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          style: FilledButton.styleFrom(
            backgroundColor:
                widget.danger ? AdminColors.danger : AdminColors.primary,
            foregroundColor: AdminColors.textOnPrimary,
          ),
          onPressed: valid ? () => Navigator.of(context).pop(reason) : null,
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}

// --------------------------------------------------------------- header

/// Who this is, at a glance: face or initials, name, reference, standing.
class _ProfileHeader extends StatelessWidget {
  const _ProfileHeader({required this.identity, required this.activity});

  final Map<String, dynamic> identity;
  final CustomerActivity activity;

  @override
  Widget build(BuildContext context) {
    final name = ((identity['name'] ?? '') as String).trim();
    final active = _yes(identity['active']);
    final lastSeen = activity.lastSessionAt ?? activity.lastOrderAt;

    final rawRoles = identity['roles'];
    final roles = rawRoles is List
        ? rawRoles.map((role) => role.toString()).toList(growable: false)
        : <String>[
            if ((identity['role'] as String?)?.isNotEmpty == true)
              identity['role'] as String,
          ];

    return AdminSectionCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          _Avatar(
              name: name, imageUrl: identity['profileImageUrl'] as String?),
          const SizedBox(width: AdminSpacing.lg),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(name.isEmpty ? 'Unnamed customer' : name,
                  style: const TextStyle(
                      fontSize: 19,
                      fontWeight: FontWeight.w700,
                      color: AdminColors.textPrimary)),
              const SizedBox(height: 2),
              Text((identity['customerRef'] ?? '') as String,
                  style: const TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: AdminColors.textSecondary)),
              const SizedBox(height: AdminSpacing.sm),
              Wrap(spacing: 6, runSpacing: 6, children: [
                AdminStatusBadge(
                  label: active ? 'Active' : 'Barred',
                  tone: active ? AdminStatusTone.success : AdminStatusTone.danger,
                ),
                AdminStatusBadge(
                  label: _yes(identity['verified']) ? 'Verified' : 'Unverified',
                  tone: _yes(identity['verified'])
                      ? AdminStatusTone.info
                      : AdminStatusTone.neutral,
                ),
                for (final role in roles)
                  AdminStatusBadge(
                    label: _pretty(role),
                    tone: AdminStatusTone.neutral,
                  ),
              ]),
            ]),
          ),
        ]),
        const Divider(height: AdminSpacing.xl),
        Row(children: [
          Expanded(
            child: _Fact(
                label: 'Joined', value: _when(_time(identity['createdAt']))),
          ),
          Expanded(
            child: _Fact(label: 'Last seen', value: _when(lastSeen)),
          ),
        ]),
      ]),
    );
  }
}

/// The customer's own photo, or their initials - never a stand-in face.
class _Avatar extends StatelessWidget {
  const _Avatar({required this.name, required this.imageUrl});

  final String name;
  final String? imageUrl;

  static const double _size = 64;

  @override
  Widget build(BuildContext context) {
    final url = imageUrl?.trim() ?? '';
    if (url.isEmpty) {
      return Container(
        width: _size,
        height: _size,
        alignment: Alignment.center,
        decoration: const BoxDecoration(
          color: AdminColors.primaryLight,
          shape: BoxShape.circle,
        ),
        child: Text(
          _initials(name),
          style: const TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.w700,
              color: AdminColors.primaryDeep),
        ),
      );
    }
    return ClipOval(
      child: SizedBox(
        width: _size,
        height: _size,
        child: GpNetworkImage(
          url: url,
          renderWidth: _size,
          fit: BoxFit.cover,
          fallbackIcon: Icons.person_outline,
          placeholderColor: AdminColors.primaryLight,
          placeholderIconColor: AdminColors.primaryDeep,
        ),
      ),
    );
  }

  /// One character, upper-cased, taken WITH runes rather than by index so a
  /// Devanagari or emoji first letter is not sliced in half into a pair of
  /// replacement boxes. Plenty of GP-STORE customers register in Hindi.
  static String _firstLetter(String word) {
    final runes = word.runes;
    if (runes.isEmpty) return '?';
    return String.fromCharCode(runes.first).toUpperCase();
  }

  /// Up to two letters from the name, or a neutral glyph when there is no
  /// name to take them from. Never a guess at who this person is.
  static String _initials(String name) {
    final words = name
        .split(RegExp(r'\s+'))
        .where((w) => w.trim().isNotEmpty)
        .toList();
    if (words.isEmpty) return '?';
    if (words.length == 1) {
      return _firstLetter(words.first);
    }
    return _firstLetter(words.first) + _firstLetter(words.last);
  }
}

// -------------------------------------------------------------- contact

class _ContactCard extends StatelessWidget {
  const _ContactCard({
    required this.identity,
    required this.addresses,
  });

  final Map<String, dynamic> identity;
  final List<Map<String, dynamic>> addresses;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      title: 'Contact and addresses',
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _ContactLine(
          icon: Icons.mail_outline,
          label: 'Email',
          value: identity['email'] as String?,
        ),
        const SizedBox(height: AdminSpacing.sm),
        _ContactLine(
          icon: Icons.call_outlined,
          label: 'Phone',
          value: identity['phone'] as String?,
        ),
        const Divider(height: AdminSpacing.xl),
        if (addresses.isEmpty)
          // SAID, NOT HIDDEN. An absent section reads as a section that
          // failed to load; this reads as a fact about the customer.
          const Row(children: [
            Icon(Icons.place_outlined, size: 16, color: AdminColors.textMuted),
            SizedBox(width: 8),
            Text('No address saved',
                style: TextStyle(fontSize: 13, color: AdminColors.textSecondary)),
          ])
        else
          for (final address in addresses) _AddressRow(address: address),
      ]),
    );
  }
}

/// Full operational contact detail for the platform owner. The backend
/// projection never includes credentials, tokens, OTPs or payment secrets.
class _ContactLine extends StatelessWidget {
  const _ContactLine({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final String? value;

  @override
  Widget build(BuildContext context) {
    final shown = (value ?? '').trim();
    if (shown.isEmpty) {
      return Row(children: [
        Icon(icon, size: 16, color: AdminColors.textMuted),
        const SizedBox(width: 8),
        Text('No $label on file'.toLowerCase().replaceFirst('no', 'No'),
            style: const TextStyle(
                fontSize: 13, color: AdminColors.textSecondary)),
      ]);
    }
    return Row(children: [
      Icon(icon, size: 16, color: AdminColors.textSecondary),
      const SizedBox(width: 8),
      Expanded(
        child: Text(shown,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
                fontSize: 13.5,
                fontWeight: FontWeight.w600,
                color: AdminColors.textPrimary)),
      ),
    ]);
  }
}

// ---------------------------------------------------------------- stats

/// Four numbers that answer "is this a real, active customer".
class _StatCards extends StatelessWidget {
  const _StatCards({
    required this.orders,
    required this.finance,
    required this.activity,
  });

  final Map<String, dynamic> orders;
  final Map<String, dynamic> finance;
  final CustomerActivity activity;

  @override
  Widget build(BuildContext context) {
    final cards = <Widget>[
      AdminKpiCard(
        icon: Icons.receipt_long_outlined,
        label: 'Orders',
        value: AdminFormat.count(_num(orders['total']).toInt()),
      ),
      AdminKpiCard(
        icon: Icons.check_circle_outline,
        label: 'Completed',
        value: AdminFormat.count(_num(orders['completed']).toInt()),
      ),
      AdminKpiCard(
        icon: Icons.currency_rupee,
        label: 'Lifetime spend',
        value: AdminFormat.rupees(_money(finance['completedPurchaseValue'])),
      ),
      AdminKpiCard(
        icon: Icons.event_available_outlined,
        label: 'Active days',
        value: AdminFormat.count(activity.activeDays),
      ),
    ];

    return LayoutBuilder(builder: (context, constraints) {
      // Two across on a phone, four on anything wide enough. Measured, not
      // assumed from a device class - this console is used on a tablet and
      // on a desktop browser as well as on a phone.
      final columns = constraints.maxWidth >= 720 ? 4 : 2;
      const spacing = AdminSpacing.md;
      final width =
          (constraints.maxWidth - spacing * (columns - 1)) / columns;
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

// -------------------------------------------------------------- finance

/// Money, to the paise, because this is the part somebody disputes.
class _FinanceCard extends StatelessWidget {
  const _FinanceCard({required this.finance, required this.orders});

  final Map<String, dynamic> finance;
  final Map<String, dynamic> orders;

  @override
  Widget build(BuildContext context) {
    final spent = _money(finance['completedPurchaseValue']);
    final refunded = _money(finance['refunds']);
    final fees = _money(finance['cancellationFees']);
    final average = _money(finance['averageCompletedOrder']);
    final lastOrder = _time(finance['lastOrderAt']);

    return AdminSectionCard(
      title: 'Money',
      subtitle: 'Completed orders only - a basket is not a purchase',
      child: Column(children: [
        _MoneyRow(label: 'Paid for goods', amount: spent),
        _MoneyRow(label: 'Refunded to them', amount: refunded),
        _MoneyRow(label: 'Cancellation charges', amount: fees),
        const Divider(height: AdminSpacing.xl),
        _MoneyRow(label: 'Net to the platform', amount: spent - refunded, bold: true),
        const SizedBox(height: AdminSpacing.md),
        Row(children: [
          Expanded(
            child: _Fact(
                label: 'Typical order',
                // A DASH, NOT Rs 0.00, for somebody who has never completed
                // an order. Zero is a number they earned; this is the
                // absence of one.
                value: average <= 0
                    ? '—'
                    : AdminFormat.rupeesExact(average)),
          ),
          Expanded(
            child: _Fact(label: 'Last order', value: _when(lastOrder)),
          ),
        ]),
        const SizedBox(height: AdminSpacing.md),
        Row(children: [
          Expanded(
            child: _Fact(
                label: 'Cancelled',
                value: AdminFormat.count(_num(orders['cancelled']).toInt())),
          ),
          Expanded(
            child: _Fact(
                label: 'Returned',
                value: AdminFormat.count(_num(orders['returned']).toInt())),
          ),
          Expanded(
            child: _Fact(
                label: 'Refunded',
                value: AdminFormat.count(_num(orders['refunded']).toInt())),
          ),
        ]),
      ]),
    );
  }
}

class _MoneyRow extends StatelessWidget {
  const _MoneyRow({required this.label, required this.amount, this.bold = false});

  final String label;
  final double amount;
  final bool bold;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(children: [
        Expanded(
          child: Text(label,
              style: TextStyle(
                  fontSize: 13,
                  fontWeight: bold ? FontWeight.w700 : FontWeight.w400,
                  color: bold
                      ? AdminColors.textPrimary
                      : AdminColors.textSecondary)),
        ),
        Text(
          AdminFormat.rupeesExact(amount),
          style: TextStyle(
            fontSize: bold ? 15 : 13.5,
            fontWeight: FontWeight.w700,
            fontFeatures: const [FontFeature.tabularFigures()],
            color: AdminColors.textPrimary,
          ),
        ),
      ]),
    );
  }
}

// -------------------------------------------------------- order history

/// Every order this customer has placed, a page at a time.
///
/// SERVER-SIDE PAGING, and it reuses the control tower's existing orders
/// resource rather than adding a second endpoint that returns orders. The
/// customer filter is applied in the database; nothing downloads the order
/// table and filters it on the phone.
class _OrderHistory extends ConsumerStatefulWidget {
  const _OrderHistory({required this.customerId});

  final int customerId;

  @override
  ConsumerState<_OrderHistory> createState() => _OrderHistoryState();
}

class _OrderHistoryState extends ConsumerState<_OrderHistory> {
  static const _pageSize = 10;

  final List<Map<String, dynamic>> _rows = [];
  int _page = 0;
  int _total = 0;
  bool _hasMore = false;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _fetch(0);
  }

  Future<void> _fetch(int page) async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await ref.read(platformRepositoryProvider).controlTowerResource(
            resource: 'orders',
            customerId: widget.customerId,
            page: page,
            size: _pageSize,
          );
      if (!mounted) return;
      setState(() {
        if (page == 0) _rows.clear();
        _rows.addAll(result.content);
        _page = result.page;
        _total = result.totalElements;
        _hasMore = result.hasMore;
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
    return AdminSectionCard(
      title: 'Order history',
      subtitle: _loading && _rows.isEmpty
          ? null
          : (_total == 0
              ? 'No orders yet'
              : 'Showing ${_rows.length} of ${AdminFormat.count(_total)}'),
      child: Column(children: [
        if (_error != null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: AdminSpacing.sm),
            child: Row(children: [
              const Icon(Icons.error_outline, size: 16, color: AdminColors.danger),
              const SizedBox(width: 8),
              Expanded(
                child: Text(_error!,
                    style: const TextStyle(
                        fontSize: 12.5, color: AdminColors.danger)),
              ),
              TextButton(
                onPressed: hapticize(() => _fetch(_page)),
                child: const Text('Retry'),
              ),
            ]),
          ),
        for (final order in _rows) _OrderRow(order: order),
        if (_loading)
          // NOT AdminListSkeleton: that is a ListView, and a ListView inside
          // this card - which is itself inside the screen's ListView - has no
          // bounded height and throws while laying out. Three bars in a
          // Column say the same thing and lay out under any constraints.
          const Padding(
            padding: EdgeInsets.symmetric(vertical: AdminSpacing.md),
            child: Column(children: [
              AdminSkeleton(height: 18),
              SizedBox(height: AdminSpacing.sm),
              AdminSkeleton(height: 18),
              SizedBox(height: AdminSpacing.sm),
              AdminSkeleton(height: 18),
            ]),
          ),
        if (!_loading && _hasMore)
          Padding(
            padding: const EdgeInsets.only(top: AdminSpacing.sm),
            child: TextButton.icon(
              onPressed: hapticize(() => _fetch(_page + 1)),
              icon: const Icon(Icons.expand_more, size: 18),
              label: const Text('Show 10 more'),
              style: TextButton.styleFrom(foregroundColor: AdminColors.primary),
            ),
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
    final status = (order['status'] ?? '') as String;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Flexible(
                child: Text((order['orderNumber'] ?? '—') as String,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontWeight: FontWeight.w700, fontSize: 13)),
              ),
              if (status.isNotEmpty) ...[
                const SizedBox(width: 6),
                AdminStatusBadge(
                  label: _pretty(status),
                  tone: AdminStatusBadge.toneForOrderStatus(status),
                  dense: true,
                ),
              ],
            ]),
            const SizedBox(height: 2),
            Text(
              [order['shop'] ?? order['shopName'], _when(_time(order['orderedAt']))]
                  .where((e) => e != null && '$e'.isNotEmpty)
                  .join(' · '),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 11.5, color: AdminColors.textSecondary),
            ),
          ]),
        ),
        const SizedBox(width: AdminSpacing.sm),
        Text(AdminFormat.rupeesExact(_money(order['total'])),
            style: const TextStyle(
                fontWeight: FontWeight.w700,
                fontSize: 13,
                fontFeatures: [FontFeature.tabularFigures()])),
      ]),
    );
  }
}

// ------------------------------------------------------------- payments

class _PaymentsCard extends StatelessWidget {
  const _PaymentsCard({required this.payments, required this.refunds});

  final List<Map<String, dynamic>> payments;
  final List<Map<String, dynamic>> refunds;

  @override
  Widget build(BuildContext context) {
    if (payments.isEmpty && refunds.isEmpty) {
      return const AdminSectionCard(
        title: 'Payments and refunds',
        child: Text('Nothing paid or refunded yet',
            style: TextStyle(fontSize: 13, color: AdminColors.textSecondary)),
      );
    }
    return AdminSectionCard(
      title: 'Payments and refunds',
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        for (final payment in payments.take(10)) _PaymentRow(payment: payment),
        if (payments.isNotEmpty && refunds.isNotEmpty)
          const Divider(height: AdminSpacing.xl),
        for (final refund in refunds.take(10))
          _MoneyRow(
            label: '${refund['orderNumber'] ?? refund['orderId'] ?? '—'} · '
                '${_pretty('${refund['status'] ?? '—'}')} refund',
            amount: _money(refund['amount']),
          ),
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
              [payment['orderNumber'], payment['method'], _pretty('${payment['status'] ?? ''}')]
                  .where((e) => e != null && '$e'.isNotEmpty)
                  .join(' · '),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
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
        Text(AdminFormat.rupeesExact(_money(payment['amount'])),
            style: const TextStyle(
                fontWeight: FontWeight.w700,
                fontSize: 12.5,
                fontFeatures: [FontFeature.tabularFigures()])),
      ]),
    );
  }
}

// ---------------------------------------------------- reviews and gripes

class _ReviewsAndComplaints extends StatelessWidget {
  const _ReviewsAndComplaints({required this.reviews, required this.returns});

  final List<Map<String, dynamic>> reviews;
  final List<Map<String, dynamic>> returns;

  @override
  Widget build(BuildContext context) {
    if (reviews.isEmpty && returns.isEmpty) {
      return const AdminSectionCard(
        title: 'Reviews and complaints',
        child: Text('They have not reviewed anything or raised a return',
            style: TextStyle(fontSize: 13, color: AdminColors.textSecondary)),
      );
    }
    final reported = reviews.where((r) => _yes(r['reported'])).length;
    return AdminSectionCard(
      title: 'Reviews and complaints',
      subtitle: reported == 0
          ? null
          : '$reported review${reported == 1 ? '' : 's'} reported by a merchant',
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        for (final review in reviews.take(10)) _ReviewRow(review: review),
        if (reviews.isNotEmpty && returns.isNotEmpty)
          const Divider(height: AdminSpacing.xl),
        for (final entry in returns.take(10))
          _MoneyRow(
            label: '${entry['orderNumber'] ?? '—'} · '
                'return ${_pretty('${entry['status'] ?? '—'}')}',
            amount: _money(entry['refundAmount']),
          ),
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

// ------------------------------------------------------------- security

/// What has been done to this account, and the one thing you can do to it.
class _SecurityCard extends StatelessWidget {
  const _SecurityCard({
    required this.identity,
    required this.security,
    required this.onChangeStatus,
  });

  final Map<String, dynamic> identity;
  final List<Map<String, dynamic>> security;
  final Future<void> Function({required bool active}) onChangeStatus;

  @override
  Widget build(BuildContext context) {
    final active = _yes(identity['active']);
    return AdminSectionCard(
      title: 'Security',
      subtitle: 'Every action here is recorded against your account',
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
          width: double.infinity,
          child: OutlinedButton.icon(
            onPressed: hapticize(() => onChangeStatus(active: !active)),
            icon: Icon(active ? Icons.block : Icons.lock_open_outlined, size: 18),
            label: Text(active ? 'Bar this account' : 'Restore this account'),
            style: OutlinedButton.styleFrom(
              foregroundColor: active ? AdminColors.danger : AdminColors.primary,
              side: BorderSide(
                  color: active ? AdminColors.danger : AdminColors.primary),
              padding: const EdgeInsets.symmetric(vertical: AdminSpacing.md),
            ),
          ),
        ),
        const SizedBox(height: AdminSpacing.lg),
        if (security.isEmpty)
          const Text('No security events recorded for this account',
              style: TextStyle(fontSize: 12.5, color: AdminColors.textSecondary))
        else ...[
          const Text('Recent events',
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.4,
                  color: AdminColors.textSecondary)),
          const SizedBox(height: AdminSpacing.sm),
          for (final entry in security.take(15)) _AuditRow(entry: entry),
        ],
      ]),
    );
  }
}

/// One audit row: who, what, from what to what, why, when.
class _AuditRow extends StatelessWidget {
  const _AuditRow({required this.entry});

  final Map<String, dynamic> entry;

  @override
  Widget build(BuildContext context) {
    final previous = '${entry['previousState'] ?? ''}'.trim();
    final next = '${entry['newState'] ?? ''}'.trim();
    final reason = '${entry['reason'] ?? ''}'.trim();
    final actor = '${entry['actorEmail'] ?? entry['actorRole'] ?? ''}'.trim();

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Text(_pretty('${entry['action'] ?? '—'}'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 12.5)),
          ),
          Text(_when(_time(entry['occurredAt'])),
              style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary)),
        ]),
        if (previous.isNotEmpty || next.isNotEmpty)
          Text(
            previous.isEmpty
                ? _pretty(next)
                : '${_pretty(previous)} → ${_pretty(next)}',
            style: const TextStyle(fontSize: 11.5, color: AdminColors.textSecondary),
          ),
        if (actor.isNotEmpty)
          Text('by $actor',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 11, color: AdminColors.textMuted)),
        if (reason.isNotEmpty)
          Text('"$reason"',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                  fontSize: 11.5,
                  fontStyle: FontStyle.italic,
                  color: AdminColors.textSecondary)),
      ]),
    );
  }
}

// ------------------------------------------------------------ behaviour

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
        Row(children: [
          Expanded(child: _Fact(label: 'Sessions', value: '${activity.sessions}')),
          Expanded(child: _Fact(label: 'Total time', value: activity.totalTimeLabel)),
        ]),
        const SizedBox(height: AdminSpacing.md),
        Row(children: [
          Expanded(
              child: _Fact(
                  label: 'First seen', value: _when(activity.firstSessionAt))),
          Expanded(
              child: _Fact(
                  label: 'Last seen', value: _when(activity.lastSessionAt))),
        ]),
        if (activity.note.isNotEmpty) ...[
          const SizedBox(height: AdminSpacing.md),
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
    final label = '${address['label'] ?? ''}'.trim();
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(_yes(address['isDefault']) ? Icons.star : Icons.place_outlined,
            size: 15,
            color: _yes(address['isDefault'])
                ? AdminColors.primary
                : AdminColors.textSecondary),
        const SizedBox(width: 6),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            if (label.isNotEmpty)
              Text(label,
                  style: const TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w700,
                      color: AdminColors.textSecondary)),
            Text(parts.isEmpty ? 'Address saved with no details' : parts,
                style: const TextStyle(fontSize: 12.5, height: 1.35)),
          ]),
        ),
      ]),
    );
  }
}

/// A small labelled figure - the unit the header and the activity card are
/// built from, so they line up without each inventing its own spacing.
class _Fact extends StatelessWidget {
  const _Fact({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label,
          style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary)),
      const SizedBox(height: 2),
      Text(value,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: AdminColors.textPrimary)),
    ]);
  }
}

// --------------------------------------------------------------- helpers

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

/// SCREAMING_SNAKE from the database into something a person reads.
/// PAYMENT_FAILED becomes Payment failed, not "PAYMENT_FAILED" on a chip.
String _pretty(String raw) {
  final words = raw.trim().replaceAll('_', ' ').toLowerCase();
  if (words.isEmpty) return raw;
  return words[0].toUpperCase() + words.substring(1);
}

/// Null becomes an em dash, never "null" and never today's date.
String _when(DateTime? value) {
  if (value == null) return '—';
  final local = value.toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(local.day)}/${two(local.month)}/${local.year} '
      '${two(local.hour)}:${two(local.minute)}';
}
