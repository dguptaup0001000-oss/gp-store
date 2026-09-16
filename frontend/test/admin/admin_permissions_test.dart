import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/auth/admin_permissions.dart';
import 'package:gpstore/admin/shell/admin_destinations.dart';

/// The client's permission mirror, and the guard that keeps it a mirror.
///
/// The server is what actually enforces access. These tests exist so the app
/// does not show somebody a screen that can only ever return 403, and so the
/// two copies of the model cannot drift apart unnoticed.
void main() {
  // The backend files this mirrors. Read from source rather than duplicated,
  // because a duplicate is exactly the thing that drifts.
  final permissionJava =
      File('../backend/src/main/java/com/gpstore/security/AdminPermission.java');
  final rolePermissionsJava =
      File('../backend/src/main/java/com/gpstore/security/RolePermissions.java');
  final roleJava = File('../backend/src/main/java/com/gpstore/entity/Role.java');

  group('drift against the backend', () {
    test('every backend permission exists here, and nothing extra', () {
      // If this fails, somebody added a permission on the server. Until it is
      // added here too, any screen behind it is either hidden from people who
      // have it or shown to people who do not.
      if (!permissionJava.existsSync()) {
        fail('cannot find AdminPermission.java at ${permissionJava.path}');
      }
      final source = permissionJava.readAsStringSync();
      // An enum constant is an ALL_CAPS identifier alone on its own
      // four-space-indented line. The trailing comma or semicolon is
      // OPTIONAL on purpose: the last constant in a Java enum has neither,
      // and requiring one silently drops it - which would have made this
      // test pass while missing a whole role.
      final backend = _enumConstants(source);

      expect(backend, isNotEmpty,
          reason: 'parsed no constants from AdminPermission.java');

      final dart =
          AdminPermission.values.map((p) => p.backendName).toSet();
      expect(dart, backend);
    });

    test('every backend staff role exists here', () {
      final source = roleJava.readAsStringSync();
      final backendRoles = _enumConstants(source);

      // CUSTOMER and DELIVERY_BOY are deliberately not staff.
      final expectedStaff = backendRoles
          .where((r) => r != 'CUSTOMER' && r != 'DELIVERY_BOY')
          .toSet();
      expect(AdminRoles.all.toSet(), expectedStaff);
    });

    test('ADMIN holds every shop permission here too', () {
      // The backend guarantees this (RolePermissions maps ADMIN to every
      // permission a SHOP role can hold). If the client disagreed, an
      // existing admin would open the console and find menu items missing.
      //
      // NOT "every permission in the enum". PLATFORM_ADMIN and CATALOG_DEFINE
      // belong to the marketplace operator, and a shopkeeper holding either
      // would be a shopkeeper with a scope spanning every other merchant.
      expect(rolePermissionsJava.readAsStringSync(),
          contains('Role.ADMIN, EVERY_SHOP_PERMISSION'));

      final shopPermissions = AdminPermission.values
          .where((p) =>
              p != AdminPermission.platformAdmin &&
              p != AdminPermission.catalogDefine &&
              p != AdminPermission.platformObservability)
          .toSet();
      expect(AdminRoles.permissionsFor(AdminRoles.admin), shopPermissions);

      // SUPER_ADMIN IS NOT A SHOP ROLE. It is the platform owner, and it is
      // required to EXCEED the shopkeeper rather than equal them. The two used
      // to be the same set, which handed the shopkeeper the marketplace's
      // observability and withheld the cross-shop scope from the owner.
      final ownerPermissions =
          AdminRoles.permissionsFor(AdminRoles.superAdmin);
      expect(ownerPermissions, containsAll(shopPermissions));
      expect(ownerPermissions.length, greaterThan(shopPermissions.length));
      expect(ownerPermissions, contains(AdminPermission.platformAdmin));
      expect(ownerPermissions, contains(AdminPermission.platformObservability));
    });

    test('a shop role never holds a platform permission', () {
      // The client mirror of the backend's most dangerous line. If this ever
      // passes for a shop role, the admin console is offering a shopkeeper a
      // screen that acts on every merchant on the platform.
      // superAdmin is the platform OWNER and holds these by design; the
      // dangerous role is admin, which is asserted explicitly below.
      for (final role in AdminRoles.all) {
        final isPlatform = role == AdminRoles.platformAdmin ||
            role == AdminRoles.superAdmin;
        expect(
          AdminRoles.permissionsFor(role).contains(AdminPermission.platformAdmin),
          isPlatform,
          reason: role,
        );
        expect(
          AdminRoles.permissionsFor(role).contains(AdminPermission.catalogDefine),
          isPlatform,
          reason: role,
        );
        expect(
          AdminRoles.permissionsFor(role)
              .contains(AdminPermission.platformObservability),
          isPlatform,
          reason: role,
        );
      }

      // The shopkeeper, named rather than inferred: these three are what
      // separate running a shop from running the marketplace.
      final shopkeeper = AdminRoles.permissionsFor(AdminRoles.admin);
      expect(shopkeeper, isNot(contains(AdminPermission.platformAdmin)));
      expect(shopkeeper, isNot(contains(AdminPermission.catalogDefine)));
      expect(
          shopkeeper, isNot(contains(AdminPermission.platformObservability)));
    });
  });

  group('permissionsFor', () {
    test('fails closed on anything unrecognised', () {
      expect(AdminRoles.permissionsFor(null), isEmpty);
      expect(AdminRoles.permissionsFor('CUSTOMER'), isEmpty);
      expect(AdminRoles.permissionsFor('DELIVERY_BOY'), isEmpty);
      expect(AdminRoles.permissionsFor('WAREHOUSE_GOD'), isEmpty);
      expect(AdminRoles.permissionsFor(''), isEmpty);
    });

    test('is forgiving about case and padding', () {
      expect(AdminRoles.permissionsFor(' manager '),
          AdminRoles.permissionsFor(AdminRoles.manager));
    });

    test('isStaff decides who may open the console at all', () {
      // The bug this pins: AdminRootScreen used to compare role != 'ADMIN',
      // which would have told a MANAGER to go and shop instead.
      for (final role in AdminRoles.all) {
        expect(AdminRoles.isStaff(role), isTrue, reason: role);
      }
      expect(AdminRoles.isStaff('CUSTOMER'), isFalse);
      expect(AdminRoles.isStaff('DELIVERY_BOY'), isFalse);
      expect(AdminRoles.isStaff(null), isFalse);
    });

    test('counter staff can take money but not send it back', () {
      final orderManager = AdminRoles.permissionsFor(AdminRoles.orderManager);
      expect(orderManager, contains(AdminPermission.paymentsManage));
      expect(orderManager, isNot(contains(AdminPermission.paymentsRefund)));
    });

    test('only the owner sees the system surface', () {
      // platformAdmin is in this set because it is a LEGACY ALIAS for
      // superAdmin rather than a role of its own - GP-STORE has exactly one
      // highest authority, and this list names the same three roles the
      // backend's RolePermissions does. It is spelled out role by role
      // instead of derived, so a NEW role cannot arrive holding the system
      // surface without somebody editing this line.
      for (final role in AdminRoles.all) {
        final expected = role == AdminRoles.admin ||
            role == AdminRoles.superAdmin ||
            role == AdminRoles.platformAdmin;
        expect(
          AdminRoles.permissionsFor(role).contains(AdminPermission.systemAdmin),
          expected,
          reason: role,
        );
      }
    });
  });

  group('navigation filtering', () {
    test('an admin sees every destination that belongs to a shop', () {
      final groups =
          AdminNav.groupsFor(AdminRoles.permissionsFor(AdminRoles.admin));
      final labels = [
        for (final g in groups) ...g.destinations.map((d) => d.label)
      ];
      // EVERYTHING EXCEPT THE MARKETPLACE. platformAdmin is the one
      // permission no shop role holds - RolePermissions builds each shop role
      // by SUBTRACTING it - so a shop owner with every permission their own
      // shop can grant still does not run the market. The exception is named
      // rather than the set loosened, so adding a second platform-only
      // destination fails here until somebody decides it belongs.
      final shopDestinations = AdminNav.groups
          .expand((group) => group.destinations)
          .map((d) => d.label)
          .toSet();
      expect(labels.toSet(), shopDestinations);
    });

    test('no shop role can see platform navigation, and the platform role can', () {
      // The server refuses /api/platform/** regardless; this is the other
      // half - not offering a shopkeeper a door that only ever answers 403,
      // and not hiding it from the one person whose job it is.
      for (final role in AdminRoles.all) {
        final groups = AdminNav.groupsFor(AdminRoles.permissionsFor(role));
        final labels = [
          for (final g in groups) ...g.destinations.map((d) => d.label)
        ];
        // superAdmin is the platform OWNER, not a shop role, so the
        // marketplace console is exactly its job.
        if (role == AdminRoles.platformAdmin ||
            role == AdminRoles.superAdmin) {
          expect(
            labels,
            containsAll([
              'Control Tower',
              'Merchants',
              'Shops',
              'Merchant Administration',
            ]),
            reason: role,
          );
          expect(labels, isNot(contains('My Shop')), reason: role);
        } else {
          expect(labels, isNot(contains('Control Tower')), reason: role);
          expect(labels, isNot(contains('Merchant Administration')), reason: role);
        }
      }
    });

    test('support sees a short menu and no inventory or coupons', () {
      final groups =
          AdminNav.groupsFor(AdminRoles.permissionsFor(AdminRoles.support));
      final labels = [
        for (final g in groups) ...g.destinations.map((d) => d.label)
      ];
      expect(labels, contains('Orders'));
      expect(labels, contains('Customers'));
      expect(labels, contains('Reviews'));
      expect(labels, isNot(contains('Inventory')));
      expect(labels, isNot(contains('Coupons')));
      expect(labels, isNot(contains('Territories')));
      expect(labels, isNot(contains('Audit Log')));
    });

    test('an inventory manager sees stock, not people or money', () {
      final groups = AdminNav
          .groupsFor(AdminRoles.permissionsFor(AdminRoles.inventoryManager));
      final labels = [
        for (final g in groups) ...g.destinations.map((d) => d.label)
      ];
      expect(labels, containsAll(['Products', 'Inventory', 'Analytics']));
      expect(labels, isNot(contains('Customers')));
      expect(labels, isNot(contains('Payments')));
      expect(labels, isNot(contains('Delivery Workers')));
    });

    test('empty groups are dropped, never left as a bare heading', () {
      // A heading with nothing under it reads as a screen that failed to
      // load rather than one that does not apply to you.
      for (final role in AdminRoles.all) {
        final groups = AdminNav.groupsFor(AdminRoles.permissionsFor(role));
        for (final group in groups) {
          expect(group.destinations, isNotEmpty, reason: '$role / ${group.title}');
        }
      }
    });

    test('shop roles keep Dashboard while platform roles open Control Tower', () {
      for (final role in AdminRoles.all) {
        final groups = AdminNav.groupsFor(AdminRoles.permissionsFor(role));
        final ids = [for (final g in groups) ...g.destinations.map((d) => d.id)];
        if (role == AdminRoles.platformAdmin || role == AdminRoles.superAdmin) {
          expect(ids, contains(AdminNav.controlTower.id), reason: role);
          expect(ids, isNot(contains(AdminNav.dashboardId)), reason: role);
        } else {
          expect(ids, contains(AdminNav.dashboardId), reason: role);
        }
      }
    });

    test('packing is wider than the switch that stops the shop trading', () {
      // Deliberately different permissions. Whoever packs the boxes reads the
      // list all morning; pausing orders stops the shop earning and belongs
      // with whoever decides when the vans run.
      final support = AdminRoles.permissionsFor(AdminRoles.support);
      final packing = AdminNav.all.firstWhere((d) => d.label == 'Packing List');
      final hours = AdminNav.all.firstWhere((d) => d.label == 'Store Hours');

      expect(AdminNav.isVisible(packing, support), isTrue);
      expect(AdminNav.isVisible(hours, support), isFalse);

      final deliveryManager =
          AdminRoles.permissionsFor(AdminRoles.deliveryManager);
      expect(AdminNav.isVisible(hours, deliveryManager), isTrue);

      // A counter clerk takes orders; they do not close the shop.
      expect(
        AdminNav.isVisible(
            hours, AdminRoles.permissionsFor(AdminRoles.orderManager)),
        isFalse,
      );
    });

    test('bulk catalogue import is owner-only, not a catalogue role', () {
      // One upload can rewrite every price in the shop. SecurityConfig gates
      // /api/admin/catalog/** on SYSTEM_ADMIN, so listing this any wider would
      // only show a catalogue role a link that returns 403.
      final import =
          AdminNav.all.firstWhere((d) => d.label == 'Import Catalogue');
      expect(import.requires, AdminPermission.systemAdmin);

      expect(
        AdminNav.isVisible(
            import, AdminRoles.permissionsFor(AdminRoles.admin)),
        isTrue,
      );
      for (final role in [
        AdminRoles.manager,
        AdminRoles.inventoryManager,
        AdminRoles.orderManager,
        AdminRoles.support,
      ]) {
        expect(
          AdminNav.isVisible(import, AdminRoles.permissionsFor(role)),
          isFalse,
          reason: role,
        );
      }
    });

    test('a destination a role cannot use is not visible to it', () {
      final inventory =
          AdminNav.all.firstWhere((d) => d.label == 'Inventory');
      expect(
        AdminNav.isVisible(
            inventory, AdminRoles.permissionsFor(AdminRoles.support)),
        isFalse,
      );
      expect(
        AdminNav.isVisible(
            inventory, AdminRoles.permissionsFor(AdminRoles.admin)),
        isTrue,
      );
    });
  });

  group('humanize', () {
    test('a backend enum name never reaches the screen verbatim', () {
      expect(AdminRoles.humanize('INVENTORY_MANAGER'), 'Inventory Manager');
      expect(AdminRoles.humanize('SUPPORT'), 'Support');
      expect(AdminRoles.humanize(null), 'Staff');
      expect(AdminRoles.humanize(''), 'Staff');
    });
  });
}

/// Enum constant names from a Java source file.
///
/// Matches an ALL_CAPS identifier alone on a four-space-indented line, with
/// the trailing comma or semicolon optional - the final constant in a Java
/// enum has neither. Javadoc lines start with an asterisk and field
/// declarations carry more on the line, so neither is matched.
Set<String> _enumConstants(String javaSource) {
  return RegExp(r'^ {4}([A-Z][A-Z_]{2,})\s*[,;]?\s*$', multiLine: true)
      .allMatches(javaSource)
      .map((m) => m.group(1)!)
      .toSet();
}
