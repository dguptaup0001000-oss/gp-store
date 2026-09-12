import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../core/api/error_messages.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/platform_models.dart';
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
class PlatformConsoleScreen extends ConsumerWidget {
  const PlatformConsoleScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Marketplace'),
          bottom: const TabBar(
            tabs: [
              Tab(text: 'Merchants'),
              Tab(text: 'Shops'),
              Tab(text: 'Overview'),
            ],
          ),
        ),
        body: const TabBarView(
          children: [_MerchantsTab(), _ShopsTab(), _OverviewTab()],
        ),
      ),
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
            ],
          ),
        ],
      ),
    );
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
            ],
          ),
        ],
      ),
    );
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
