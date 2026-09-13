import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/platform_models.dart';
import 'package:gpstore/features/admin/presentation/platform_console_screen.dart';
import 'package:gpstore/features/admin/presentation/platform_providers.dart';
import 'package:gpstore/features/admin/presentation/platform_shop_card.dart';

/// How many shops each business runs, on the list where the decision is made.
///
/// WHY IT MATTERS ON THE LIST AND NOT ONLY ON THE DETAIL SCREEN. Suspending a
/// merchant closes every shop under it. The platform owner usually taps
/// Suspend from this list, so the list is where "this is one shop" and "this
/// is four" has to be visible - reaching the detail screen first is a habit,
/// not a guarantee.
void main() {
  const business = MerchantView(
    id: 6,
    merchantRef: 'M-000006',
    legalName: 'Gupta Hardware',
    displayName: 'Gupta Hardware',
    status: 'ACTIVE',
  );

  PlatformShopView shop(int id, int merchantId) => PlatformShopView(
        id: id,
        shopRef: 'S-${id.toString().padLeft(6, '0')}',
        merchantId: merchantId,
        code: 'SHOP-$id',
        displayName: 'Shop $id',
        status: 'ACTIVE',
      );

  Widget host({
    required List<MerchantView> merchants,
    required Future<List<PlatformShopView>> Function() shops,
  }) =>
      ProviderScope(
        overrides: [
          platformMerchantsProvider.overrideWith((ref) async => merchants),
          platformShopsProvider.overrideWith((ref) => shops()),
        ],
        child: const MaterialApp(home: PlatformConsoleScreen()),
      );

  int? shopCountShown(WidgetTester tester) {
    final facts = tester
        .widgetList<PlatformFact>(find.byType(PlatformFact))
        .where((f) => f.label == 'Shops')
        .toList();
    if (facts.isEmpty) return null;
    return int.parse(facts.single.value);
  }

  testWidgets('a business running three shops says three on its card',
      (tester) async {
    await tester.pumpWidget(host(
      merchants: [business],
      shops: () async => [shop(11, 6), shop(12, 6), shop(13, 6)],
    ));
    await tester.pumpAndSettle();

    expect(shopCountShown(tester), 3);
  });

  testWidgets('shops belonging to another business are not counted in',
      (tester) async {
    await tester.pumpWidget(host(
      merchants: [business],
      // The list endpoint returns every shop on the platform. Counting them
      // all would tell the platform owner that suspending Gupta Hardware
      // closes five shops, two of which belong to somebody else.
      shops: () async =>
          [shop(11, 6), shop(12, 6), shop(20, 7), shop(21, 7), shop(22, 7)],
    ));
    await tester.pumpAndSettle();

    expect(shopCountShown(tester), 2);
  });

  testWidgets('a shop with no merchant on it is counted against nobody',
      (tester) async {
    await tester.pumpWidget(host(
      merchants: [business],
      shops: () async => [shop(11, 6), const PlatformShopView(id: 99)],
    ));
    await tester.pumpAndSettle();

    expect(shopCountShown(tester), 1);
  });

  testWidgets('when the shop list will not load, the merchants still list',
      (tester) async {
    await tester.pumpWidget(host(
      merchants: [business],
      shops: () async => throw Exception('shops are down'),
    ));
    await tester.pumpAndSettle();

    // THE MERCHANT LIST IS WHAT THIS TAB IS FOR. Failing the whole tab over a
    // count would turn a missing subtitle into a screen that cannot be used
    // to suspend anybody.
    expect(find.text('Gupta Hardware'), findsWidgets);
    // "0" would be a lie: nobody knows yet. Saying nothing is the honest
    // answer, and it is the difference between "no shops" and "not known".
    expect(shopCountShown(tester), isNull);
  });
}
