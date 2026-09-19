import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../core/api/error_messages.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/merchant_transitions.dart';
import '../domain/platform_models.dart';
import 'platform_onboarding_forms.dart';
import 'platform_providers.dart';
import 'platform_shop_card.dart';

/// One business, and every shop trading under it.
///
/// THE BUSINESS IS NOT THE SHOP. That distinction is the whole reason this
/// screen exists: the console's two tabs list merchants and shops as if they
/// were parallel, and they are not - a merchant owns one shop or six, and
/// suspending the merchant stops all of them at once. A platform owner about
/// to suspend Deepak Enterprises needs to see, on the same screen and before
/// they tap, that three storefronts go dark.
///
/// READ IN ONE CALL. `/merchants/{id}/detail` returns the merchant and its
/// shops together, and the shops were selected by merchant id on the server.
/// Two calls stitched together here would leave a window in which this screen
/// shows one merchant's heading above another merchant's storefronts.
class PlatformMerchantDetailScreen extends ConsumerWidget {
  const PlatformMerchantDetailScreen({super.key, required this.merchantId});

  final int merchantId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detailAsync = ref.watch(platformMerchantDetailProvider(merchantId));

    return Scaffold(
      appBar: AppBar(
        title: Text(detailAsync.valueOrNull?.merchant.displayName ??
            detailAsync.valueOrNull?.merchant.legalName ??
            'Merchant'),
      ),
      floatingActionButton: switch (detailAsync.valueOrNull?.merchant) {
        // ONLY WHERE IT CAN WORK. The server opens a shop under an APPROVED or
        // ACTIVE merchant and refuses the rest, so offering the button on a
        // business still in APPLICATION is a tap that ends in a refusal
        // explaining nothing about what to do first.
        final MerchantView m when _canHoldAShop(m) =>
          FloatingActionButton.extended(
            onPressed: hapticize(() => _addShop(context, ref, m)),
            icon: const Icon(Icons.add_business_outlined),
            label: const Text('Add shop'),
          ),
        _ => null,
      },
      body: detailAsync.when(
        loading: () =>
            const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text("Couldn't load this merchant: ${extractErrorMessage(error)}",
                    textAlign: TextAlign.center),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: hapticize(() => ref
                      .invalidate(platformMerchantDetailProvider(merchantId))),
                  child: const Text('Retry'),
                ),
              ],
            ),
          ),
        ),
        data: (detail) => RefreshIndicator(
          onRefresh: () async =>
              ref.invalidate(platformMerchantDetailProvider(merchantId)),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
            children: [
              _TheBusiness(merchant: detail.merchant, shopCount: detail.shopCount),
              const SizedBox(height: 16),
              _ShopsHeading(count: detail.shopCount),
              const SizedBox(height: 8),
              // THE BUTTON THAT WAS MISSING. Saying a merchant has nothing to
              // sell from is true and was, until now, the end of the
              // conversation: a business registered without a shop could only
              // be fixed by deleting it and starting again. A real one -
              // GUPT SAREE - sat in exactly this state while its owner was
              // told their account was not associated with a shop.
              if (detail.shops.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Column(
                    children: [
                      const Text(
                        'No shops under this business yet. A merchant with no '
                        'shop has nothing to sell from, and their sign-in has '
                        'nowhere to go.',
                        textAlign: TextAlign.center,
                        style: TextStyle(fontSize: 13),
                      ),
                      const SizedBox(height: 12),
                      FilledButton.icon(
                        onPressed: hapticize(
                            () => _addFirstShop(context, ref, detail.merchant)),
                        icon: const Icon(Icons.add_business_outlined),
                        label: const Text('Add the first shop'),
                      ),
                    ],
                  ),
                ),
              for (final shop in detail.shops)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: PlatformShopCard(shop: shop, showMerchant: false),
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// What `ShopLifecycleService.open` will accept. The server re-checks,
  /// because a merchant can be suspended between this screen being drawn and
  /// the form being submitted.
  static bool _canHoldAShop(MerchantView m) =>
      m.status == 'APPROVED' || m.status == 'ACTIVE';

  Future<void> _addShop(
      BuildContext context, WidgetRef ref, MerchantView merchant) async {
    // THE MERCHANT IS NOT A CHOICE HERE. Opening this form from the console's
    // Shops tab means picking a business out of a list, which is where a shop
    // gets attached to the wrong one. Reached from the business itself there
    // is nothing to pick, and the one-element list is what makes the dialog
    // preselect it.
    final created = await showDialog<PlatformShopView>(
      context: context,
      builder: (_) => PlatformShopFormDialog(merchants: [merchant]),
    );
    if (created == null || !context.mounted) return;
    ref.invalidate(platformMerchantDetailProvider(merchantId));
    ref.invalidate(platformShopsProvider);
    platformSay(
      context,
      '${created.displayName ?? created.code ?? 'Shop'} opened as '
      '${created.status ?? 'DRAFT'}. Stock it, set its hours, then open it on '
      'the marketplace.',
    );
  }
}

class _ShopsHeading extends StatelessWidget {
  const _ShopsHeading({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Text(
      count == 1 ? '1 shop' : '$count shops',
      style: Theme.of(context).textTheme.titleMedium,
    );
  }
}

class _TheBusiness extends ConsumerWidget {
  const _TheBusiness({required this.merchant, required this.shopCount});

  final MerchantView merchant;
  final int shopCount;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final offered = MerchantTransitions.from(merchant.status);

    return AdminSectionCard(
      title: 'The business',
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
            PlatformFact(
                label: 'Owner account', value: '${merchant.ownerCustomerId}'),
          PlatformFact(label: 'Shops', value: '$shopCount'),
          const SizedBox(height: 10),
          // SAID BEFORE THE BUTTON IS TAPPED, not after. Suspending a merchant
          // cascades to every shop under it, and "it also closed the other two"
          // is not something to find out from a customer.
          if (shopCount > 1 &&
              (offered.contains('SUSPENDED') || offered.contains('PAUSED')))
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                'Pausing or suspending this business stops all $shopCount of '
                'its shops.',
                style: TextStyle(
                    fontSize: 12, color: Theme.of(context).colorScheme.error),
              ),
            ),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final status in offered)
                OutlinedButton(
                  onPressed: hapticize(() => _move(context, ref, status)),
                  child: Text(MerchantTransitions.label(status)),
                ),
              if (offered.isEmpty)
                const Text(
                  'This business is closed. Its records stay.',
                  style: TextStyle(fontSize: 12),
                ),
            ],
          ),
          if (merchant.ownerCustomerId != null)
            MerchantOwnerRecovery(ownerCustomerId: merchant.ownerCustomerId!),
        ],
      ),
    );
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
      ref.invalidate(platformMerchantDetailProvider(merchant.id));
      ref.invalidate(platformMerchantsProvider);
      // A merchant's status decides whether its shops may trade, so the shop
      // list is stale the moment this returns.
      ref.invalidate(platformShopsProvider);
      if (context.mounted) platformSay(context, 'Merchant moved to $status.');
    } catch (error) {
      if (context.mounted) platformSay(context, extractErrorMessage(error));
    }
  }
}


/// Opens the first-shop dialog and says what came back.
///
/// TOP-LEVEL RATHER THAN A METHOD because this screen is a ConsumerWidget with
/// no state of its own, and giving it one just to hold a dialog call would be
/// the wrong trade.
Future<void> _addFirstShop(
    BuildContext context, WidgetRef ref, MerchantView merchant) async {
  final made = await showDialog<AddedFirstShop>(
    context: context,
    builder: (_) => PlatformAddFirstShopDialog(
      merchantId: merchant.id,
      businessName:
          merchant.displayName ?? merchant.legalName ?? 'This business',
      merchantStatus: merchant.status,
    ),
  );
  if (made == null || !context.mounted) return;
  ref.invalidate(platformMerchantDetailProvider(merchant.id));

  // SAYS WHAT HAPPENS NEXT. The shop exists and the owner can now sign in,
  // but it is a draft with empty shelves - so the operator is told the one
  // remaining step rather than left wondering why customers cannot see it.
  final approved = made.merchantStatusBefore != made.merchantStatusAfter;
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
    content: Text(
      'Shop "${made.shopCode ?? made.shopId}" is open as a draft'
      '${approved ? ' and the business is now ${made.merchantStatusAfter}' : ''}. '
      'The owner can sign in. Once they have put stock up, press Let them trade.',
    ),
  ));
}
