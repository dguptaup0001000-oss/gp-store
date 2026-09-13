import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/admin/domain/shop_admin_models.dart';
import '../../features/admin/presentation/shop_self_service_providers.dart';
import 'shop_switch.dart';

/// Which shop am I working in, and how do I change it.
///
/// ONE SHOP IS THE ORDINARY CASE AND STAYS ORDINARY (§58, §59). A shopkeeper
/// with a single kirana must not be handed a marketplace administration
/// console: with one shop this renders NOTHING AT ALL, and the app behaves
/// exactly as the single-shop app it has always been. The switcher appears
/// when there is something to switch between, which is the only time it means
/// anything.
///
/// THE NAME IS ALWAYS ON SCREEN once there are two, and that is a safety
/// feature rather than decoration (§64). Before this, the admin shell showed
/// no shop name anywhere - there was only ever one shop, so there was nothing
/// to be wrong about. With three, a merchant who believes they are in GP Store
/// and is actually in Deepak Hardware will change the wrong prices and not
/// find out until a customer complains. A label costs one line of chrome and
/// removes that entire category of mistake.
class ShopSwitcherBar extends ConsumerWidget {
  const ShopSwitcherBar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final shops = ref.watch(myShopsProvider);
    return shops.when(
      // SILENT WHILE LOADING AND SILENT ON FAILURE. This sits in the chrome of
      // every screen; a spinner or a red banner here would interrupt a
      // merchant's whole app because one secondary call was slow. If we cannot
      // say which shop this is, saying nothing is better than saying something
      // that might be wrong.
      loading: () => const SizedBox.shrink(),
      error: (_, __) => const SizedBox.shrink(),
      data: (value) {
        final choices = value.shops;
        if (choices.length < 2) {
          return const SizedBox.shrink();
        }
        final acting = _acting(value);
        return _Bar(current: acting, choices: choices);
      },
    );
  }

  static ShopChoice? _acting(MyShops value) {
    for (final choice in value.shops) {
      if (choice.acting || choice.shopId == value.acting) {
        return choice;
      }
    }
    return value.shops.isEmpty ? null : value.shops.first;
  }
}

class _Bar extends ConsumerWidget {
  const _Bar({required this.current, required this.choices});

  final ShopChoice? current;
  final List<ShopChoice> choices;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    return Material(
      color: theme.colorScheme.surfaceContainerHighest,
      child: InkWell(
        onTap: () => _open(context, ref),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          child: Row(
            children: [
              const Icon(Icons.storefront_outlined, size: 18),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  shopLabel(current),
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleSmall
                      ?.copyWith(fontWeight: FontWeight.w600),
                ),
              ),
              const Text('Switch'),
              const Icon(Icons.arrow_drop_down),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _open(BuildContext context, WidgetRef ref) async {
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      // SCROLLABLE, AND NOT BECAUSE OF THE THREE SHOPS IN THE EXAMPLE. The
      // architecture puts no cap on how many shops a merchant may have, so a
      // fixed-height column is a layout that works until the day somebody
      // opens their fifth - on a short phone, sooner. A widget test caught
      // this overflowing at 289 logical pixels.
      isScrollControlled: true,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const ListTile(
              title: Text('My shops'),
              subtitle: Text('Everything below changes to the shop you pick'),
            ),
            const Divider(height: 1),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
            for (final choice in choices)
              ListTile(
                leading: Icon(choice.shopId == current?.shopId
                    ? Icons.check_circle
                    : Icons.storefront_outlined),
                title: Text(shopLabel(choice)),
                subtitle: Text(_subtitle(choice)),
                // A SHOP THAT CANNOT BE ADMINISTERED IS NOT OFFERED. Closed,
                // or its merchant removed: tapping it would open a screen that
                // errors on arrival, which reads as the app being broken
                // rather than the shop being shut.
                enabled: choice.operable,
                onTap: choice.operable
                    ? () {
                        Navigator.of(sheetContext).pop();
                        switchToShop(ref, choice.shopId);
                      }
                    : null,
              ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  static String _subtitle(ShopChoice choice) {
    final status = choice.status ?? 'UNKNOWN';
    return choice.operable ? status : '$status - nothing to manage here';
  }
}

/// "Deepak Hardware (S-000002)", or the best we can do.
///
/// The reference is included because two shops in one business are often named
/// alike - "Gupta Store" and "Gupta Store 2" - and the reference is the half
/// that is never ambiguous.
String shopLabel(ShopChoice? choice) {
  if (choice == null) {
    return 'This shop';
  }
  final name = (choice.displayName != null && choice.displayName!.trim().isNotEmpty)
      ? choice.displayName!.trim()
      : (choice.code ?? 'Shop ${choice.shopId}');
  return name;
}

/// Asks before something destructive, and SAYS WHICH SHOP (§64).
///
/// "Delete this product?" is a question a merchant answers yes to without
/// reading. "Delete Sugar 1kg from Deepak Hardware?" is one they stop at when
/// they thought they were in GP Store. The shop name is the whole value of
/// this helper; everything else is an ordinary dialog.
Future<bool> confirmForShop(
  BuildContext context,
  WidgetRef ref, {
  required String action,
  required String subject,
}) async {
  final shops = ref.read(myShopsProvider).valueOrNull;
  final several = (shops?.shops.length ?? 0) > 1;
  final where = several ? ' from ${shopLabel(ShopSwitcherBar._acting(shops!))}' : '';
  final answer = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text('$action $subject$where?'),
      content: several
          ? Text('This changes ${shopLabel(ShopSwitcherBar._acting(shops!))} '
              'and no other shop.')
          : null,
      actions: [
        TextButton(
          onPressed: () => Navigator.of(dialogContext).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(dialogContext).pop(true),
          child: Text(action),
        ),
      ],
    ),
  );
  return answer ?? false;
}
