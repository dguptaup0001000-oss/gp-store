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

    test('ADMIN holds every permission here too', () {
      // The backend guarantees this (RolePermissions maps ADMIN to the
      // complete set). If the client disagreed, an existing admin would open
      // the console and find menu items missing.
      expect(rolePermissionsJava.readAsStringSync(),
          contains('Role.ADMIN, EVERYTHING'));
      expect(AdminRoles.permissionsFor(AdminRoles.admin),
          AdminPermission.values.toSet());
      expect(AdminRoles.permissionsFor(AdminRoles.superAdmin),
          AdminPermission.values.toSet());
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
      for (final role in AdminRoles.all) {
        final expected =
            role == AdminRoles.admin || role == AdminRoles.superAdmin;
        expect(
          AdminRoles.permissionsFor(role).contains(AdminPermission.systemAdmin),
          expected,
          reason: role,
        );
      }
    });
  });

  group('navigation filtering', () {
    test('an admin sees every destination', () {
      final groups =
          AdminNav.groupsFor(AdminRoles.permissionsFor(AdminRoles.admin));
      final labels = [
        for (final g in groups) ...g.destinations.map((d) => d.label)
      ];
      expect(labels.toSet(), AdminNav.all.map((d) => d.label).toSet());
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
      expect(labels, isNot(contains('Delivery Partners')));
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

    test('every role keeps the dashboard, so nobody lands nowhere', () {
      for (final role in AdminRoles.all) {
        final groups = AdminNav.groupsFor(AdminRoles.permissionsFor(role));
        final ids = [for (final g in groups) ...g.destinations.map((d) => d.id)];
        expect(ids, contains(AdminNav.dashboardId), reason: role);
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
