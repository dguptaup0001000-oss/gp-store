import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/shell/admin_shell.dart';
import 'package:gpstore/admin/shell/admin_destinations.dart';
import 'package:gpstore/features/admin/domain/shop_admin_models.dart';
import 'package:gpstore/features/admin/presentation/shop_self_service_providers.dart';

import '../support/test_api_client.dart';

/// The phone drawer says who you are signed in as.
///
/// WHY THIS IS WORTH A TEST. The sidebar is filtered by the role's
/// permissions, so a destination somebody cannot find is missing BECAUSE of
/// their role - and on a phone the role appeared nowhere at all. The wide
/// layout has always named it in its top bar; the compact layout is a bare
/// AppBar reading "Dashboard", so the one question the filtering provokes
/// ("why can I not see Merchants & Shops?") had no answer anywhere on the
/// screen.
///
/// A real support conversation, not a hypothetical: an ADMIN account looking
/// for the Marketplace group, which only the platform owner holds, with
/// nothing in the app to say which of the two it was signed in as.
void main() {
  // The shell's body is the dashboard, which watches providers and reads
  // secure storage. Neither is what these tests are about; both have to exist
  // or the shell cannot build at all.
  setUpAll(setUpFakeSecureStorage);

  /// Narrow enough for the drawer, wide enough for the dashboard behind it.
  ///
  /// Only AdminBreakpoints.isExpanded (1024) switches the shell to the
  /// permanent sidebar, so anything under that builds the compact layout this
  /// is about. 900 rather than a true phone width because the body is the
  /// dashboard, whose period chips overflow at 360dp under the test font -
  /// a pre-existing overflow in a widget these tests do not touch, which
  /// would otherwise fail every one of them for the wrong reason.
  Future<void> pumpPhone(WidgetTester tester, Widget child) async {
    tester.view.physicalSize = const Size(900, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
        ProviderScope(child: MaterialApp(home: child)));
    await tester.pumpAndSettle();
  }

  Future<void> openDrawer(WidgetTester tester) async {
    tester.state<ScaffoldState>(find.byType(Scaffold).first).openDrawer();
    await tester.pumpAndSettle();
  }

  testWidgets('the drawer names the role, humanised', (tester) async {
    await pumpPhone(
        tester, const AdminShell(operatorName: 'Deepak', role: 'SUPER_ADMIN'));
    await openDrawer(tester);

    expect(find.text('Deepak'), findsOneWidget);
    expect(find.text('Super Admin'), findsOneWidget,
        reason: 'the role is the information on this line - it is what '
            'decides which groups below it exist');
  });

  testWidgets('a shop owner is told they are a shop owner', (tester) async {
    await pumpPhone(tester, const AdminShell(role: 'ADMIN'));
    await openDrawer(tester);

    // The case that sent somebody hunting for a group they can never see.
    expect(find.text('Admin'), findsOneWidget);
    expect(find.text('Super Admin'), findsNothing);
  });

  testWidgets('an unknown or absent role still says something', (tester) async {
    await pumpPhone(tester, const AdminShell(operatorName: 'Somebody'));
    await openDrawer(tester);

    // FAILS OPEN INTO A WORD, not into a blank line. AdminRoles.humanize
    // answers "Staff" for null, and a drawer that renders nothing here would
    // look like a layout bug rather than a missing claim.
    expect(find.text('Staff'), findsOneWidget);
  });

  testWidgets('the name is decoration and the role is not', (tester) async {
    await pumpPhone(tester, const AdminShell(role: 'DELIVERY_MANAGER'));
    await openDrawer(tester);

    // No operatorName at all: the role must still be shown, because it is the
    // half that answers the question.
    expect(find.text('Delivery Manager'), findsOneWidget);
  });

  testWidgets('the platform owner never gets a merchant shop switcher',
      (tester) async {
    tester.view.physicalSize = const Size(900, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        myShopsProvider.overrideWith((ref) async => const MyShops(
              shops: [
                ShopChoice(
                  shopId: 1,
                  displayName: 'GP Store',
                  status: 'ACTIVE',
                  operable: true,
                  acting: true,
                ),
                ShopChoice(
                  shopId: 2,
                  displayName: 'Second Shop',
                  status: 'ACTIVE',
                  operable: true,
                ),
              ],
              acting: 1,
            )),
      ],
      child: const MaterialApp(
        home: AdminShell(role: 'SUPER_ADMIN'),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Switch'), findsNothing,
        reason: 'shop switching is a merchant-admin tool, not a platform-owner tool');
  });

  testWidgets('the platform owner gets platform navigation, not merchant tools',
      (tester) async {
    await pumpPhone(
        tester,
        const AdminShell(
          home: AdminNav.controlTower,
          role: 'SUPER_ADMIN',
        ));
    await openDrawer(tester);

    expect(find.text('Control Tower'), findsOneWidget);
    expect(find.text('Merchants & Shops'), findsOneWidget);
    expect(find.text('My Shop'), findsNothing);
    expect(find.text('Receipt Printer'), findsNothing);
    expect(find.text('Store Hours'), findsNothing);
  });

  testWidgets('non-owner shop staff never get the merchant shop switcher',
      (tester) async {
    tester.view.physicalSize = const Size(900, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        myShopsProvider.overrideWith((ref) async => const MyShops(
              shops: [
                ShopChoice(
                  shopId: 1,
                  displayName: 'GP Store',
                  status: 'ACTIVE',
                  operable: true,
                  acting: true,
                ),
                ShopChoice(
                  shopId: 2,
                  displayName: 'Second Shop',
                  status: 'ACTIVE',
                  operable: true,
                ),
              ],
              acting: 1,
            )),
      ],
      child: const MaterialApp(
        home: AdminShell(role: 'ORDER_MANAGER'),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Switch'), findsNothing,
        reason: 'only the merchant-owner ADMIN role owns shop switching');
  });
}
