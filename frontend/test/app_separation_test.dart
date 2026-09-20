import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/shared/app_kind.dart';

void main() {
  _cashfreeGroup();

  test('customer profile has no admin Store Management entry', () {
    final src = File('lib/features/profile/presentation/profile_screen.dart')
        .readAsStringSync();
    expect(src.contains('Store Management'), isFalse);
    expect(src.contains('admin_home_screen'), isFalse);
    expect(src.contains("features/admin/"), isFalse);
  });

  test('customer entrypoint does not import admin features', () {
    bool importsAdmin(String src) =>
        src.contains("features/admin/") ||
        src.contains("admin_home_screen") ||
        src.contains("admin_main.dart");
    expect(importsAdmin(File('lib/customer_main.dart').readAsStringSync()),
        isFalse);
    expect(importsAdmin(File('lib/customer/customer_app.dart').readAsStringSync()),
        isFalse);
    expect(importsAdmin(File('lib/customer/customer_root.dart').readAsStringSync()),
        isFalse);
  });

  test('admin entrypoint does not import the shopping shell', () {
    final src = File('lib/admin/admin_app.dart').readAsStringSync() +
        File('lib/admin/admin_root.dart').readAsStringSync();
    expect(src.contains('customer_shell'), isFalse);
    expect(src.contains('CustomerShell'), isFalse);
    expect(src.contains('features/cart/'), isFalse);
    expect(src.contains('features/checkout/'), isFalse);
  });

  test('AppKind follows the build identity and defaults safely', () {
    const raw = String.fromEnvironment('GPSTORE_APP', defaultValue: 'customer');
    final expected = switch (raw) {
      'admin' => AppKind.admin,
      'superadmin' => AppKind.superAdmin,
      _ => AppKind.customer,
    };

    expect(AppKind.current, expected);
    expect(AppKind.tokenKeyPrefix, AppKind.prefixFor(expected));
  });

  test('super admin entrypoint does not import the shopping shell', () {
    final src = File('lib/admin/super_admin_app.dart').readAsStringSync() +
        File('lib/admin/super_admin_root.dart').readAsStringSync();
    expect(src.contains('customer_shell'), isFalse);
    expect(src.contains('CustomerShell'), isFalse);
    expect(src.contains('features/cart/'), isFalse);
    expect(src.contains('features/checkout/'), isFalse);
  });

  test('super admin entrypoint never initialises Firebase', () {
    // NOT STYLE - THIS ONE BREAKS THE BUILD. in.gpstore.superadmin has no
    // client entry in the Firebase project, so android/app/build.gradle skips
    // the google-services plugin for that flavor. Calling
    // bootstrapGpstoreApp here would reach Firebase.initializeApp(), land in
    // its catch block, and log what reads as a misconfiguration on every
    // launch of an app that correctly has no push.
    // Comments stripped first. This file's own doc comment names
    // bootstrapGpstoreApp to say what it deliberately does NOT call, and a
    // test that read prose would fail on the explanation of why it passes.
    final code = File('lib/super_admin_main.dart')
        .readAsLinesSync()
        .where((line) => !line.trimLeft().startsWith('//'))
        .join('\n');

    expect(code.contains('bootstrapWithoutPush'), isTrue,
        reason: 'the super admin app must use the push-free bootstrap');
    expect(code.contains('bootstrapGpstoreApp'), isFalse);
    expect(code.toLowerCase().contains('firebase'), isFalse);
  });

  group('the four apps stay four apps', () {
    final gradle = File('android/app/build.gradle').readAsStringSync();

    test('every flavor has its own applicationId', () {
      // SHARE ONE AND INSTALLING EITHER REPLACES THE OTHER. A merchant and
      // the platform owner are signed in on the same phone during onboarding,
      // which is precisely when that would bite.
      final ids = RegExp(r'applicationId\s+"([^"]+)"')
          .allMatches(gradle)
          .map((m) => m.group(1)!)
          .toSet();

      expect(ids, containsAll(<String>{
        'in.gpstore.customer',
        'in.gpstore.admin',
        'in.gpstore.superadmin',
        'com.gpstore.worker',
      }));
    });

    test('the super admin flavor skips google-services', () {
      // The plugin fails the build outright on a package it cannot find, so
      // this condition is what keeps the flavor buildable at all.
      expect(gradle.contains('def superAdminApk'), isTrue);
      expect(gradle.contains('def noFirebaseClient = workerApk || superAdminApk'),
          isTrue);
      expect(gradle.contains('if (!noFirebaseClient) {'), isTrue);
    });
  });

  group('CI actually produces the super admin APK', () {
    final workflow =
        File('../.github/workflows/build-and-deploy.yml').readAsStringSync();

    test('it is built, renamed, verified and uploaded', () {
      // A flavor that exists in Gradle but in no CI step is a flavor nobody
      // can install - which is the state this whole change is fixing.
      expect(workflow.contains('--flavor superadmin'), isTrue);
      expect(workflow.contains('-t lib/super_admin_main.dart'), isTrue);
      expect(workflow.contains('--dart-define=GPSTORE_APP=superadmin'), isTrue);
      expect(
          workflow.contains(
              'superadmin gpstore-superadmin-release.apk gpstore-superadmin-armv7.apk'),
          isTrue,
          reason: 'split-per-abi output needs renaming to a stable artifact name');
      expect(workflow.contains('name: gpstore-superadmin-release.apk'), isTrue);
      expect(workflow.contains('name: gpstore-superadmin-armv7.apk'), isTrue);
    });

    test('its signature is verified like every other APK', () {
      expect(
          workflow.contains(
              'build/app/outputs/flutter-apk/gpstore-superadmin-release.apk \\'),
          isTrue);
    });

    test('it reports its real size', () {
      final report = File('tool/report_apk_artifacts.py').readAsStringSync();
      expect(report.contains('gpstore-superadmin-release.apk'), isTrue);
      expect(report.contains('gpstore-superadmin-armv7.apk'), isTrue);
    });
  });
}

/// Which apps can actually reach the Cashfree SDK, walked rather than assumed.
///
/// WHY THE IMPORT GRAPH AND NOT A grep. A shallow check of an entrypoint says
/// nothing: nobody imports a payment SDK from main.dart. The SDK is four hops
/// down - cashfree_checkout_service <- checkout_screen <- cart_screen <- the
/// customer shell - and only a walk of the whole closure can tell you whether
/// an app reaches it.
///
/// This is the precondition for taking the SDK out of the Super Admin and
/// Worker builds: those apps may only drop it while they genuinely never
/// execute an SDK payment flow. If somebody later routes a merchant or an
/// operator through checkout, this test fails and says so before the packaging
/// decision silently becomes wrong.
///
/// SUPER ADMIN STILL SEES MONEY. Dropping the client SDK is not dropping
/// payment visibility: payments and refunds reach the console through
/// /api/platform/control/payments and /refunds, which are backend reads
/// behind PLATFORM_ADMIN and have nothing to do with the checkout SDK. The
/// assertion below is about the SDK only.
Set<String> _importClosure(String entrypoint) {
  final seen = <String>{};
  final queue = <String>[entrypoint];
  final external = <String>{};

  while (queue.isNotEmpty) {
    final path = queue.removeLast();
    if (!seen.add(path)) continue;
    final file = File(path);
    if (!file.existsSync()) continue;

    for (final line in file.readAsLinesSync()) {
      final match = RegExp(
              '''^\\s*(?:import|export)\\s+['"]([^'"]+)['"]''')
          .firstMatch(line);
      if (match == null) continue;
      final target = match.group(1)!;

      if (target.startsWith('package:gpstore/')) {
        queue.add('lib/${target.substring('package:gpstore/'.length)}');
      } else if (target.startsWith('package:') || target.startsWith('dart:')) {
        external.add(target);
      } else {
        // Relative to the importing file's own directory.
        final dir = path.substring(0, path.lastIndexOf('/'));
        queue.add(File('$dir/$target').uri.normalizePath().toFilePath());
      }
    }
  }
  return external;
}

void _cashfreeGroup() {
  const sdk = 'package:flutter_cashfree_pg_sdk/';

  bool reachesCashfree(String entrypoint) =>
      _importClosure(entrypoint).any((p) => p.startsWith(sdk));

  test('the customer app reaches the Cashfree SDK, because customers pay', () {
    expect(reachesCashfree('lib/customer_main.dart'), isTrue,
        reason: 'if this fails, online checkout is gone from the customer app');
  });

  test('Super Admin never reaches the Cashfree SDK', () {
    expect(reachesCashfree('lib/super_admin_main.dart'), isFalse,
        reason: 'the platform console reads payments through the backend, '
            'and must not carry a client payment SDK');
  });

  test('the Worker app never reaches the Cashfree SDK', () {
    expect(reachesCashfree('lib/worker_main.dart'), isFalse,
        reason: 'a rider collects cash; they do not run a gateway checkout');
  });

  test('Merchant Admin never reaches the Cashfree SDK either', () {
    // Recorded rather than required: today the merchant console runs no
    // gateway checkout, which is worth knowing before anyone decides which
    // builds need the SDK.
    expect(reachesCashfree('lib/admin_main.dart'), isFalse);
  });
}
