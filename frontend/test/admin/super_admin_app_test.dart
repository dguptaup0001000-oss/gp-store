import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/admin_root.dart';
import 'package:gpstore/admin/super_admin_root.dart';
import 'package:gpstore/features/profile/domain/profile_models.dart';
import 'package:gpstore/features/profile/presentation/profile_providers.dart';
import 'package:gpstore/shared/app_kind.dart';

import '../support/test_api_client.dart';

/// The platform owner's app opens on the thing only they can do.
///
/// WHAT THIS IS FIXING, and it is not hypothetical: registering a merchant,
/// opening their shop and handing over their one-time password lived as the
/// LAST group of a sidebar, inside an app whose home screen is one shop's
/// trading day. The person who owns the marketplace could not find it, twice,
/// on their own phone. A separate APK whose first screen is Merchants & Shops
/// is the fix; these tests are what stop it drifting back.
void main() {
  setUpAll(setUpFakeSecureStorage);

  /// Phone width, which is where the problem actually was.
  ///
  /// 900 rather than a true 360dp: the admin dashboard's period chips
  /// overflow under the test font at phone width - a pre-existing overflow in
  /// a widget these tests do not touch - and 900 is still well under
  /// AdminBreakpoints.expanded (1024), so the compact layout is what builds.
  Future<void> pumpAs(WidgetTester tester, Widget app, String role) async {
    tester.view.physicalSize = const Size(900, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        myProfileProvider.overrideWith((ref) async => Profile(
              id: 1,
              fullName: 'Deepak',
              email: 'owner@gpstore.test',
              role: role,
            )),
      ],
      child: MaterialApp(home: app),
    ));
    await tester.pumpAndSettle();
  }

  group('the super admin app', () {
    testWidgets('opens on Merchants & Shops, not a shop dashboard',
        (tester) async {
      await pumpAs(tester, const SuperAdminRootScreen(), 'SUPER_ADMIN');

      // The app bar names the home destination, and the home destination is
      // the platform console. This is the whole reason the APK exists.
      expect(find.widgetWithText(AppBar, 'Merchants & Shops'), findsOneWidget);
      expect(find.widgetWithText(AppBar, 'Dashboard'), findsNothing);
    });

    testWidgets('the PLATFORM_ADMIN alias reaches it too', (tester) async {
      // The alias grants exactly SUPER_ADMIN's permission set. An account
      // still carrying it must not be shown the wrong-app screen - it holds
      // platformAdmin, so the console is not a dead end for it.
      await pumpAs(tester, const SuperAdminRootScreen(), 'PLATFORM_ADMIN');

      expect(find.widgetWithText(AppBar, 'Merchants & Shops'), findsOneWidget);
    });

    testWidgets('a shop owner is told which app is theirs', (tester) async {
      await pumpAs(tester, const SuperAdminRootScreen(), 'ADMIN');

      // ADMIN holds every permission a SHOP can grant and still not
      // platformAdmin - RolePermissions builds it by subtracting that one.
      // Showing them a console whose every call returns 403 would be worse
      // than showing them nothing, and saying nothing at all would leave
      // them stuck.
      expect(find.textContaining('platform owner'), findsOneWidget);
      expect(find.textContaining('GP-STORE Admin'), findsOneWidget);
      expect(find.widgetWithText(AppBar, 'Merchants & Shops'), findsNothing);
    });

    testWidgets('a customer is refused as well', (tester) async {
      await pumpAs(tester, const SuperAdminRootScreen(), 'CUSTOMER');

      expect(find.textContaining('platform owner'), findsOneWidget);
    });
  });

  group('the admin app is unchanged by this', () {
    testWidgets('a shop owner still opens on the dashboard', (tester) async {
      // The home destination is a parameter with a default. If that default
      // ever moved, every merchant's app would open somewhere new.
      await pumpAs(tester, const AdminRootScreen(), 'ADMIN');

      expect(find.widgetWithText(AppBar, 'Dashboard'), findsOneWidget);
    });

    testWidgets('the platform owner may still use it', (tester) async {
      // The two apps are not exclusive, and the admin APK must not start
      // refusing the owner just because a dedicated one now exists.
      await pumpAs(tester, const AdminRootScreen(), 'SUPER_ADMIN');

      expect(find.widgetWithText(AppBar, 'Dashboard'), findsOneWidget);
    });
  });

  group('AppKind', () {
    test('uses the configured app identity and defaults safely', () {
      const raw =
          String.fromEnvironment('GPSTORE_APP', defaultValue: 'customer');
      final expected = switch (raw) {
        'admin' => AppKind.admin,
        'superadmin' => AppKind.superAdmin,
        _ => AppKind.customer,
      };

      expect(AppKind.current, expected);
      expect(AppKind.isSuperAdmin, expected == AppKind.superAdmin);
      expect(AppKind.tokenKeyPrefix, AppKind.prefixFor(expected));
    });

    test('no two apps share a token prefix', () {
      // Two staff APKs on one phone during onboarding - the merchant's and
      // the owner's - must not be able to read each other's session if a
      // build ever reused an applicationId. A new AppKind that forgot its own
      // prefix would fall through to somebody else's.
      final prefixes = [for (final kind in AppKind.values) AppKind.prefixFor(kind)];

      expect(prefixes.toSet().length, AppKind.values.length,
          reason: 'duplicate prefixes in $prefixes');
      expect(AppKind.prefixFor(AppKind.superAdmin), 'superadmin_');
      expect(AppKind.prefixFor(AppKind.admin), 'admin_');
    });
  });
}
