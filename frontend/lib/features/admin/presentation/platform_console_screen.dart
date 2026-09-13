import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../core/api/error_messages.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/merchant_transitions.dart';
import '../domain/platform_models.dart';
import 'platform_merchant_detail_screen.dart';
import 'platform_onboarding_forms.dart';
import 'platform_providers.dart';
import 'platform_shop_card.dart';

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
        // ONLY ON THE MERCHANTS TAB, because the one thing in it registers a
        // business, and an overflow menu that is present on every tab and
        // holds nothing relevant to two of them is a menu people learn to
        // ignore.
        actions: _tabs.index == 0
            ? [
                PopupMenuButton<String>(
                  tooltip: 'More merchant actions',
                  onSelected: (value) {
                    if (value == 'register') _registerMerchant();
                  },
                  itemBuilder: (_) => const [
                    PopupMenuItem<String>(
                      value: 'register',
                      child: ListTile(
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.assignment_outlined),
                        title: Text('Register a business only'),
                        subtitle: Text('No login, no shop - paperwork first'),
                      ),
                    ),
                  ],
                ),
              ]
            : null,
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
        // ONBOARD, NOT REGISTER. Registering leaves a business in
        // APPLICATION that cannot hold a shop yet, which is not what anybody
        // opening this button wants - they want a merchant who can sell.
        // Registering on its own is still possible - it is in the overflow
        // menu above, for the case where the papers arrive before the person
        // who will run the shop does.
        0 => FloatingActionButton.extended(
            onPressed: hapticize(_onboardMerchant),
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

  Future<void> _onboardMerchant() async {
    final opened = await showDialog<OnboardedMerchant>(
      context: context,
      builder: (_) => const PlatformOnboardMerchantDialog(),
    );
    if (opened == null || !mounted) return;
    platformSay(
      context,
      '${opened.businessName ?? 'Merchant'} is approved and shop '
      '"${opened.shopCode ?? opened.shopId}" is open. Once they have put '
      'stock up, press Let them trade.',
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
    platformSay(
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
      if (mounted) platformSay(context, extractErrorMessage(error));
      return;
    }
    if (!mounted) return;
    final created = await showDialog<PlatformShopView>(
      context: context,
      builder: (_) => PlatformShopFormDialog(merchants: merchants),
    );
    if (created == null || !mounted) return;
    platformSay(
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
    // HOW MANY SHOPS EACH BUSINESS HAS, counted from the list the Shops tab
    // already loads rather than by asking per merchant. One extra request for
    // the whole screen, cached, against one request per card - and the answer
    // the platform owner is looking for ("who has more than one?") needs every
    // merchant's count at once anyway.
    //
    // A SEPARATE `.when` DELIBERATELY NOT USED. The merchant list is what this
    // tab is for; if the shop list is still loading or refused, every card
    // still draws and simply says nothing about shops, rather than the whole
    // tab failing over a subtitle.
    final shopsByMerchant = <int, int>{};
    for (final shop in ref.watch(platformShopsProvider).valueOrNull ??
        const <PlatformShopView>[]) {
      final owner = shop.merchantId;
      if (owner != null) {
        shopsByMerchant[owner] = (shopsByMerchant[owner] ?? 0) + 1;
      }
    }

    return merchantsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      error: (error, _) => _Failed(
        what: 'merchants',
        error: error,
        onRetry: () => ref.invalidate(platformMerchantsProvider),
      ),
      data: (merchants) => RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(platformMerchantsProvider);
          ref.invalidate(platformShopsProvider);
        },
        child: merchants.isEmpty
            ? const _Empty(message: 'No merchants yet.')
            : ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: merchants.length,
                separatorBuilder: (_, __) => const SizedBox(height: 12),
                itemBuilder: (context, index) => _MerchantCard(
                  merchant: merchants[index],
                  shopCount: shopsByMerchant[merchants[index].id],
                ),
              ),
      ),
    );
  }
}

class _MerchantCard extends ConsumerWidget {
  const _MerchantCard({required this.merchant, this.shopCount});

  final MerchantView merchant;

  /// Null while the shop list has not arrived (or was refused), which is why
  /// the line is omitted rather than shown as zero: "no shops" and "not known
  /// yet" are different answers and only one of them is a problem.
  final int? shopCount;

  List<String> get _offered => MerchantTransitions.from(merchant.status);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return AdminSectionCard(
      title: merchant.displayName ?? merchant.legalName ?? 'Merchant ${merchant.id}',
      subtitle: merchant.legalName,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (merchant.merchantRef != null)
            PlatformFact(label: 'Merchant ID', value: merchant.merchantRef!),
          PlatformFact(label: 'Status', value: merchant.status ?? 'Unknown'),
          if (merchant.statusReason != null)
            PlatformFact(label: 'Reason', value: merchant.statusReason!),
          if (merchant.contactPhone != null)
            PlatformFact(label: 'Phone', value: merchant.contactPhone!),
          if (merchant.contactEmail != null)
            PlatformFact(label: 'Email', value: merchant.contactEmail!),
          if (merchant.ownerCustomerId != null)
            PlatformFact(label: 'Owner account', value: '${merchant.ownerCustomerId}'),
          // ON THE CARD, not only on the detail screen. Whether a business runs
          // one shop or four changes what suspending it does, and the list is
          // where that decision usually gets made.
          if (shopCount != null)
            PlatformFact(
                label: 'Shops', value: shopCount == 1 ? '1' : '$shopCount'),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              // THE WAY IN TO THE SHOPS THEMSELVES. A merchant with several
              // storefronts cannot show them all on a list card without the
              // card becoming the screen, so the card says how many and this
              // opens them.
              OutlinedButton.icon(
                onPressed: hapticize(() => _open(context)),
                icon: const Icon(Icons.storefront_outlined, size: 18),
                label: const Text('Shops'),
              ),
              for (final status in _offered)
                OutlinedButton(
                  onPressed: hapticize(() => _move(context, ref, status)),
                  child: Text(MerchantTransitions.label(status)),
                ),
              if (_offered.isEmpty)
                const Text(
                  'This business is closed. Its records stay.',
                  style: TextStyle(fontSize: 12),
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
              // THE OTHER HALF OF THE FIRST LOGIN. A merchant who lost the
              // activation code before claiming their account cannot be given
              // the old one - it is stored as a fingerprint and nothing can
              // read it back - so the only answer is a new one, which kills
              // the old in the same instant.
              if (merchant.ownerCustomerId != null)
                TextButton.icon(
                  onPressed: hapticize(() => _reissueCode(context, ref)),
                  icon: const Icon(Icons.pin_outlined, size: 18),
                  label: const Text('New activation code'),
                ),
            ],
          ),
        ],
      ),
    );
  }

  void _open(BuildContext context) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => PlatformMerchantDetailScreen(merchantId: merchant.id),
    ));
  }

  Future<void> _reissueCode(BuildContext context, WidgetRef ref) async {
    final owner = merchant.ownerCustomerId;
    if (owner == null) return;
    final reason = await askPlatformReason(context, 'New activation code');
    if (reason == null || !context.mounted) return;
    try {
      final issued = await ref
          .read(platformRepositoryProvider)
          .reissueActivationCode(customerId: owner, reason: reason);
      if (!context.mounted) return;
      await showOneTimePassword(context, issued);
    } catch (error) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(error))));
    }
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
      if (context.mounted) platformSay(context, extractErrorMessage(error));
    }
  }

  Future<void> _move(BuildContext context, WidgetRef ref, String status) async {
    final reason =
        await askPlatformReason(context, MerchantTransitions.label(status));
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
      ref.invalidate(platformMerchantDetailProvider(merchant.id));
      if (context.mounted) platformSay(context, 'Merchant moved to $status.');
    } catch (error) {
      if (context.mounted) platformSay(context, extractErrorMessage(error));
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
                itemBuilder: (context, index) => PlatformShopCard(shop: shops[index]),
              ),
      ),
    );
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
                  PlatformFact(
                      label: 'Orders',
                      value: '${overview.totals?.orderCount ?? 0}'),
                  PlatformFact(
                    label: 'Gross sales',
                    value: '₹${(overview.totals?.grossSales ?? 0).toStringAsFixed(0)}',
                  ),
                  PlatformFact(
                    label: 'Refunds',
                    value: '₹${(overview.totals?.refunds ?? 0).toStringAsFixed(0)}',
                  ),
                  PlatformFact(label: 'Shops trading', value: '${overview.shops.length}'),
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
                      PlatformFact(label: 'Orders', value: '${line.orderCount}'),
                      PlatformFact(
                        label: 'Gross sales',
                        value: '₹${line.grossSales.toStringAsFixed(0)}',
                      ),
                      PlatformFact(
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
