import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../core/api/error_messages.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/platform_models.dart';
import 'platform_onboarding_forms.dart';
import 'platform_providers.dart';

/// Running the marketplace: who is on it, and whether they may trade.
///
/// EVERY ROUTE BEHIND THIS SCREEN REQUIRES PERM_PLATFORM_ADMIN, enforced
/// server-side. No shop role holds it - RolePermissions builds each shop role
/// by SUBTRACTING it - so a shop owner with every permission their own shop
/// can grant is still refused. This screen therefore does no gating of its
/// own beyond not being listed in the sidebar: a 403 from these calls is the
/// real answer and is shown, never worked around.
///
/// A STATUS CHANGE ASKS FOR A REASON AND MEANS IT. "Somebody looked at this
/// business's papers and decided" is a real event, and one recorded with no
/// reason is one nobody can account for later - including the merchant, who
/// is shown it.
class PlatformConsoleScreen extends ConsumerStatefulWidget {
  const PlatformConsoleScreen({super.key});

  @override
  ConsumerState<PlatformConsoleScreen> createState() =>
      _PlatformConsoleScreenState();
}

class _PlatformConsoleScreenState extends ConsumerState<PlatformConsoleScreen>
    with SingleTickerProviderStateMixin {
  // ITS OWN CONTROLLER RATHER THAN DefaultTabController, because the action
  // button has to know which tab is showing: "Register a merchant" and "Open
  // a shop" are different jobs and one button that did both would be a menu.
  late final TabController _tabs;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 3, vsync: this)
      ..addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Marketplace'),
        bottom: TabBar(
          controller: _tabs,
          tabs: const [
            Tab(text: 'Merchants'),
            Tab(text: 'Shops'),
            Tab(text: 'Overview'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabs,
        children: const [_MerchantsTab(), _ShopsTab(), _OverviewTab()],
      ),
      // NOTHING TO CREATE ON THE OVERVIEW TAB, so no button there rather than
      // a disabled one: a button that is present and dead is a worse answer
      // than no button.
      floatingActionButton: switch (_tabs.index) {
        0 => FloatingActionButton.extended(
            onPressed: hapticize(_registerMerchant),
            icon: const Icon(Icons.store_mall_directory_outlined),
            label: const Text('Merchant'),
          ),
        1 => FloatingActionButton.extended(
            onPressed: hapticize(_openShop),
            icon: const Icon(Icons.add_business_outlined),
            label: const Text('Shop'),
          ),
        _ => null,
      },
    );
  }

  Future<void> _registerMerchant() async {
    final created = await showDialog<MerchantView>(
      context: context,
      builder: (_) => const PlatformMerchantFormDialog(),
    );
    if (created == null || !mounted) return;
    // SAYS WHAT HAPPENS NEXT, because what happened is not what the owner
    // wanted: they wanted a shop, and they have a business in APPLICATION
    // that cannot hold one yet.
    _say(
      context,
      '${created.displayName ?? created.legalName ?? 'Merchant'} registered as '
      '${created.status ?? 'APPLICATION'}. Approve it before opening a shop.',
    );
  }

  Future<void> _openShop() async {
    // AWAITED, NOT READ OFF THE CACHE, and this is not a nicety.
    // TabBarView builds a tab lazily, so somebody who opens the console and
    // goes straight to Shops has never triggered the merchants provider.
    // `.valueOrNull` is then null, which the dialog would faithfully report
    // as "no merchant is ready to hold a shop" - the most discouraging
    // possible way to be wrong, and it would look like the feature was
    // broken rather than the list unloaded.
    //
    // Read ONCE here rather than watched, so the list cannot change under
    // somebody mid-form.
    final List<MerchantView> merchants;
    try {
      merchants = await ref.read(platformMerchantsProvider.future);
    } catch (error) {
      if (mounted) _say(context, extractErrorMessage(error));
      return;
    }
    if (!mounted) return;
    final created = await showDialog<PlatformShopView>(
      context: context,
      builder: (_) => PlatformShopFormDialog(merchants: merchants),
    );
    if (created == null || !mounted) return;
    _say(
      context,
      '${created.displayName ?? created.code ?? 'Shop'} opened as '
      '${created.status ?? 'DRAFT'}. Stock it, set its hours, then open it on '
      'the marketplace.',
    );
  }
}

class _MerchantsTab extends ConsumerWidget {
  const _MerchantsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final merchantsAsync = ref.watch(platformMerchantsProvider);

    return merchantsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, _) => _Failed(
        what: 'merchants',
        error: error,
        onRetry: () => ref.invalidate(platformMerchantsProvider),
      ),
      data: (merchants) => RefreshIndicator(
        onRefresh: () async => ref.invalidate(platformMerchantsProvider),
        child: merchants.isEmpty
            ? const _Empty(message: 'No merchants yet.')
            : ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: merchants.length,
                separatorBuilder: (_, __) => const SizedBox(height: 12),
                itemBuilder: (context, index) =>
                    _MerchantCard(merchant: merchants[index]),
              ),
      ),
    );
  }
}

class _MerchantCard extends ConsumerWidget {
  const _MerchantCard({required this.merchant});

  final MerchantView merchant;

  /// The moves the platform can make on a business, in the order a reviewer
  /// meets them. Mirrors MerchantStatus; the backend re-checks the value and
  /// refuses anything it does not know.
  static const _statuses = [
    'PENDING_REVIEW',
    'VERIFICATION_REQUIRED',
    'APPROVED',
    'ACTIVE',
    'SUSPENDED',
    'REJECTED',
    'REMOVED',
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return AdminSectionCard(
      title: merchant.displayName ?? merchant.legalName ?? 'Merchant ${merchant.id}',
      subtitle: merchant.legalName,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _Fact(label: 'Status', value: merchant.status ?? 'Unknown'),
          if (merchant.statusReason != null)
            _Fact(label: 'Reason', value: merchant.statusReason!),
          if (merchant.contactPhone != null)
            _Fact(label: 'Phone', value: merchant.contactPhone!),
          if (merchant.contactEmail != null)
            _Fact(label: 'Email', value: merchant.contactEmail!),
          if (merchant.ownerCustomerId != null)
            _Fact(label: 'Owner account', value: '${merchant.ownerCustomerId}'),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final status in _statuses)
                if (status != merchant.status)
                  OutlinedButton(
                    onPressed: hapticize(() => _move(context, ref, status)),
                    child: Text(_label(status)),
                  ),
              // THE ONLY RECOVERY for a merchant who cannot get in. There is
              // deliberately no route that reads their current password, so
              // "I lost it" and "it leaked" have the same answer: issue a
              // new one and end every session the old one holds.
              if (merchant.ownerCustomerId != null)
                TextButton.icon(
                  onPressed: hapticize(() => _resetOwnerPassword(context, ref)),
                  icon: const Icon(Icons.key_outlined, size: 18),
                  label: const Text('Reset password'),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _resetOwnerPassword(BuildContext context, WidgetRef ref) async {
    final owner = merchant.ownerCustomerId;
    if (owner == null) return;
    // ASKED FIRST, because this is destructive in a way the other buttons
    // here are not: it ends the merchant's sessions and the password they
    // chose stops working. Doing that by a mis-tap while they are mid-order
    // is worth one confirmation.
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Reset their password?'),
        content: const Text(
          'They will be signed out everywhere and the password they chose '
          'will stop working. You get a new one-time password to hand over, '
          'shown once.',
          style: TextStyle(fontSize: 13),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Reset'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;

    try {
      final opened = await ref
          .read(platformRepositoryProvider)
          .resetStaffPassword(customerId: owner);
      if (!context.mounted) return;
      await showOneTimePassword(context, opened);
    } catch (error) {
      if (context.mounted) _say(context, extractErrorMessage(error));
    }
  }

  static String _label(String status) => switch (status) {
        'APPROVED' => 'Approve',
        'ACTIVE' => 'Let them trade',
        'SUSPENDED' => 'Suspend',
        'REJECTED' => 'Reject',
        'REMOVED' => 'Remove',
        'PENDING_REVIEW' => 'Send for review',
        'VERIFICATION_REQUIRED' => 'Ask for documents',
        _ => status,
      };

  Future<void> _move(BuildContext context, WidgetRef ref, String status) async {
    final reason = await _askForReason(context, _label(status));
    if (reason == null || !context.mounted) return;
    try {
      await ref.read(platformRepositoryProvider).setMerchantStatus(
            merchantId: merchant.id,
            status: status,
            reason: reason.isEmpty ? null : reason,
          );
      ref.invalidate(platformMerchantsProvider);
      // A merchant's status decides whether its shops may trade, so the shop
      // list is stale the moment this returns.
      ref.invalidate(platformShopsProvider);
      if (context.mounted) _say(context, 'Merchant moved to $status.');
    } catch (error) {
      if (context.mounted) _say(context, extractErrorMessage(error));
    }
  }
}

class _ShopsTab extends ConsumerWidget {
  const _ShopsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final shopsAsync = ref.watch(platformShopsProvider);

    return shopsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, _) => _Failed(
        what: 'shops',
        error: error,
        onRetry: () => ref.invalidate(platformShopsProvider),
      ),
      data: (shops) => RefreshIndicator(
        onRefresh: () async => ref.invalidate(platformShopsProvider),
        child: shops.isEmpty
            ? const _Empty(message: 'No shops yet.')
            : ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: shops.length,
                separatorBuilder: (_, __) => const SizedBox(height: 12),
                itemBuilder: (context, index) => _ShopCard(shop: shops[index]),
              ),
      ),
    );
  }
}

class _ShopCard extends ConsumerWidget {
  const _ShopCard({required this.shop});

  final PlatformShopView shop;

  /// Mirrors ShopStatus. CLOSED is terminal on the server, and is offered
  /// last for that reason.
  static const _statuses = ['DRAFT', 'ACTIVE', 'PAUSED', 'SUSPENDED', 'CLOSED'];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return AdminSectionCard(
      title: shop.displayName ?? 'Shop ${shop.id}',
      subtitle: shop.code,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _Fact(label: 'Status', value: shop.status ?? 'Unknown'),
          if (shop.statusReason != null)
            _Fact(label: 'Reason', value: shop.statusReason!),
          if (shop.merchantId != null)
            _Fact(label: 'Merchant', value: '${shop.merchantId}'),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final status in _statuses)
                if (status != shop.status)
                  OutlinedButton(
                    onPressed: hapticize(() => _move(context, ref, status)),
                    child: Text(_label(status)),
                  ),
              // BESIDE THE STATUS MOVES, because it belongs to the same job
              // and is the step most easily missed. A shop whose merchant was
              // registered without an owner account has no staff at all, and
              // nothing about the shop looks wrong until somebody tries to
              // sign in to it.
              TextButton.icon(
                onPressed: hapticize(() => _addStaff(context, ref)),
                icon: const Icon(Icons.person_add_alt_1_outlined, size: 18),
                label: const Text('Staff'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _addStaff(BuildContext context, WidgetRef ref) async {
    final added = await showDialog<bool>(
      context: context,
      builder: (_) => PlatformStaffDialog(shop: shop),
    );
    if (added != true || !context.mounted) return;
    _say(context, 'Added to this shop\u2019s staff.');
  }

  static String _label(String status) => switch (status) {
        'ACTIVE' => 'Open on the marketplace',
        'PAUSED' => 'Pause',
        'SUSPENDED' => 'Suspend',
        'CLOSED' => 'Close for good',
        'DRAFT' => 'Back to draft',
        _ => status,
      };

  Future<void> _move(BuildContext context, WidgetRef ref, String status) async {
    final reason = await _askForReason(context, _label(status));
    if (reason == null || !context.mounted) return;
    try {
      await ref.read(platformRepositoryProvider).setShopStatus(
            shopId: shop.id,
            status: status,
            reason: reason.isEmpty ? null : reason,
          );
      ref.invalidate(platformShopsProvider);
      if (context.mounted) _say(context, 'Shop moved to $status.');
    } catch (error) {
      if (context.mounted) _say(context, extractErrorMessage(error));
    }
  }
}

class _OverviewTab extends ConsumerWidget {
  const _OverviewTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final overviewAsync = ref.watch(marketOverviewProvider(30));

    return overviewAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, _) => _Failed(
        what: 'the market overview',
        error: error,
        onRetry: () => ref.invalidate(marketOverviewProvider(30)),
      ),
      data: (overview) => RefreshIndicator(
        onRefresh: () async => ref.invalidate(marketOverviewProvider(30)),
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            AdminSectionCard(
              title: 'The market, last 30 days',
              child: Column(
                children: [
                  _Fact(
                      label: 'Orders',
                      value: '${overview.totals?.orderCount ?? 0}'),
                  _Fact(
                    label: 'Gross sales',
                    value: '₹${(overview.totals?.grossSales ?? 0).toStringAsFixed(0)}',
                  ),
                  _Fact(
                    label: 'Refunds',
                    value: '₹${(overview.totals?.refunds ?? 0).toStringAsFixed(0)}',
                  ),
                  _Fact(label: 'Shops trading', value: '${overview.shops.length}'),
                ],
              ),
            ),
            const SizedBox(height: 12),
            // PER SHOP, BECAUSE THE PLATFORM'S JOB IS THE MARKET AND NOT ONE
            // SHOP'S BOOKS. There is no commission column: under W1 each
            // merchant collects directly, and the commission model (W2) has
            // not been decided. Showing a zero would look like a decision.
            for (final line in overview.shops)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: AdminSectionCard(
                  title: 'Shop ${line.shopId}',
                  child: Column(
                    children: [
                      _Fact(label: 'Orders', value: '${line.orderCount}'),
                      _Fact(
                        label: 'Gross sales',
                        value: '₹${line.grossSales.toStringAsFixed(0)}',
                      ),
                      _Fact(
                        label: 'Refunds',
                        value: '₹${line.refunds.toStringAsFixed(0)}',
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// Asks why, before anything is changed.
///
/// Returns null when the reviewer backs out, an empty string when they went
/// ahead without typing one - which the repository turns into no reason at
/// all rather than an empty one.
Future<String?> _askForReason(BuildContext context, String action) {
  final controller = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text(action),
      content: TextField(
        controller: controller,
        autofocus: true,
        decoration: const InputDecoration(
          labelText: 'Reason',
          hintText: 'Shown to the merchant, and kept',
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(dialogContext).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(dialogContext).pop(controller.text.trim()),
          child: const Text('Confirm'),
        ),
      ],
    ),
  );
}

void _say(BuildContext context, String message) {
  ScaffoldMessenger.of(context)
      .showSnackBar(SnackBar(content: Text(message)));
}

class _Failed extends StatelessWidget {
  const _Failed({required this.what, required this.error, required this.onRetry});

  final String what;
  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text("Couldn't load $what: ${extractErrorMessage(error)}",
                textAlign: TextAlign.center),
            const SizedBox(height: 8),
            TextButton(onPressed: hapticize(onRetry), child: const Text('Retry')),
          ],
        ),
      ),
    );
  }
}

class _Empty extends StatelessWidget {
  const _Empty({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(32),
      children: [Center(child: Text(message))],
    );
  }
}

class _Fact extends StatelessWidget {
  const _Fact({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(fontSize: 13)),
          Flexible(
            child: Text(value,
                textAlign: TextAlign.right,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w600)),
          ),
        ],
      ),
    );
  }
}
