import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../core/api/error_messages.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/platform_models.dart';
import 'platform_onboarding_forms.dart';
import 'platform_providers.dart';

/// One shop as the platform sees it, with the moves it may make.
///
/// SHARED BY THE SHOPS TAB AND THE MERCHANT DETAIL SCREEN. The same shop has
/// to behave the same way whichever list it was reached from - a shop that can
/// be suspended from one screen and not the other is a shop the platform owner
/// has to remember a route to.
class PlatformShopCard extends ConsumerWidget {
  const PlatformShopCard({super.key, required this.shop, this.showMerchant = true});

  final PlatformShopView shop;

  /// False inside a merchant's own detail screen, where naming the merchant on
  /// every row repeats the heading the reader is already under.
  final bool showMerchant;

  /// Mirrors ShopStatus. CLOSED is terminal on the server, and is offered
  /// last for that reason.
  static const statuses = ['DRAFT', 'ACTIVE', 'PAUSED', 'SUSPENDED', 'CLOSED'];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return AdminSectionCard(
      title: shop.displayName ?? 'Shop ${shop.id}',
      subtitle: shop.code,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (shop.shopRef != null)
            PlatformFact(label: 'Shop ID', value: shop.shopRef!),
          PlatformFact(label: 'Status', value: shop.status ?? 'Unknown'),
          if (shop.statusReason != null)
            PlatformFact(label: 'Reason', value: shop.statusReason!),
          if (showMerchant && shop.merchantId != null)
            PlatformFact(
                label: 'Merchant',
                value: shop.merchantRef ?? '${shop.merchantId}'),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final status in statuses)
                if (status != shop.status)
                  OutlinedButton(
                    onPressed: hapticize(() => _move(context, ref, status)),
                    child: Text(label(status)),
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
    platformSay(context, 'Added to this shop’s staff.');
  }

  static String label(String status) => switch (status) {
        'ACTIVE' => 'Open on the marketplace',
        'PAUSED' => 'Pause',
        'SUSPENDED' => 'Suspend',
        'CLOSED' => 'Close for good',
        'DRAFT' => 'Back to draft',
        _ => status,
      };

  Future<void> _move(BuildContext context, WidgetRef ref, String status) async {
    final reason = await askPlatformReason(context, label(status));
    if (reason == null || !context.mounted) return;
    try {
      await ref.read(platformRepositoryProvider).setShopStatus(
            shopId: shop.id,
            status: status,
            reason: reason.isEmpty ? null : reason,
          );
      ref.invalidate(platformShopsProvider);
      // The merchant detail screen holds its own copy of this shop, read in
      // the same call as the merchant. Leaving it alone shows the old status
      // beside the snackbar announcing the new one.
      if (shop.merchantId != null) {
        ref.invalidate(platformMerchantDetailProvider(shop.merchantId!));
      }
      if (context.mounted) platformSay(context, 'Shop moved to $status.');
    } catch (error) {
      if (context.mounted) platformSay(context, extractErrorMessage(error));
    }
  }
}

/// Getting a merchant's owner back into their own account.
///
/// TWO BUTTONS BECAUSE THERE ARE TWO SECRETS and they are lost separately. A
/// merchant who never received the activation code needs a new code, not a new
/// password - reissuing the password as well would sign them out of a session
/// they are happily using. Neither button can read the old secret back: both
/// are stored as hashes, deliberately, so "I lost it" and "it leaked" have the
/// same answer.
///
/// ON BOTH SCREENS THAT SHOW A MERCHANT. A recovery that exists on the list
/// card and not on the detail screen is a recovery the platform owner has to
/// remember a route to, at the moment they are least able to.
class MerchantOwnerRecovery extends ConsumerWidget {
  const MerchantOwnerRecovery({super.key, required this.ownerCustomerId});

  final int ownerCustomerId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        TextButton.icon(
          onPressed: hapticize(() => _resetPassword(context, ref)),
          icon: const Icon(Icons.key_outlined, size: 18),
          label: const Text('Reset password'),
        ),
        TextButton.icon(
          onPressed: hapticize(() => _reissueCode(context, ref)),
          icon: const Icon(Icons.pin_outlined, size: 18),
          label: const Text('New activation code'),
        ),
      ],
    );
  }

  Future<void> _reissueCode(BuildContext context, WidgetRef ref) async {
    final reason = await askPlatformReason(context, 'New activation code');
    if (reason == null || !context.mounted) return;
    try {
      final issued = await ref
          .read(platformRepositoryProvider)
          .reissueActivationCode(customerId: ownerCustomerId, reason: reason);
      if (!context.mounted) return;
      await showOneTimePassword(context, issued);
    } catch (error) {
      if (context.mounted) platformSay(context, extractErrorMessage(error));
    }
  }

  Future<void> _resetPassword(BuildContext context, WidgetRef ref) async {
    // ASKED FIRST, because this is destructive in a way the other buttons here
    // are not: it ends the merchant's sessions and the password they chose
    // stops working. Doing that by a mis-tap while they are mid-order is worth
    // one confirmation.
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
          .resetStaffPassword(customerId: ownerCustomerId);
      if (!context.mounted) return;
      await showOneTimePassword(context, opened);
    } catch (error) {
      if (context.mounted) platformSay(context, extractErrorMessage(error));
    }
  }
}

/// Asks why, before anything is changed.
///
/// Returns null when the reviewer backs out, an empty string when they went
/// ahead without typing one - which the repository turns into no reason at
/// all rather than an empty one.
Future<String?> askPlatformReason(BuildContext context, String action) {
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

void platformSay(BuildContext context, String message) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
}

/// One labelled line of a record. Right-aligned value, so a column of them
/// reads down rather than needing to be hunted across.
class PlatformFact extends StatelessWidget {
  const PlatformFact({super.key, required this.label, required this.value});

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
