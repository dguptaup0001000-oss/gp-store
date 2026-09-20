import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/auth/admin_permissions.dart';
import 'package:gpstore/admin/shell/admin_destinations.dart';
import 'package:gpstore/features/admin/presentation/merchant_mode_catalogue_screen.dart';

/// A merchant must be able to REACH every way they are allowed to sell.
///
/// <h2>Why this test exists</h2>
///
/// Commerce modes shipped with an enum, a migration, API routes, a validated
/// edit form and thirty-odd passing backend tests - and a merchant could not
/// find them. The only door was Products, open a variant, scroll to a section
/// headed "How you sell this". The drawer said:
///
///   Products · Categories · Inventory · Import Catalogue · Coupons
///
/// A previous report called that feature complete on the strength of the
/// backend tests. It was not complete: a jeweller opening the app had no way
/// to list a single item they wanted customers to come and see.
///
/// <h2>What it asserts</h2>
///
/// Not that a widget renders - that is the part that was never in doubt - but
/// that the NAVIGATION contains the entries, under the group a merchant would
/// look in, visible to the permission a merchant actually holds, and that
/// each one builds the screen for its own mode. That is the specific thing
/// that was missing, so it is the specific thing pinned here.
void main() {
  /// A shop owner, who holds every catalogue permission their shop can give.
  /// Import Catalogue is gated on systemAdmin and Coupons on couponsManage,
  /// so a set without those legitimately hides two entries - which is why the
  /// full-order assertion below uses the owner rather than a narrower role.
  Set<AdminPermission> merchant() => {
        AdminPermission.catalogView,
        AdminPermission.catalogManage,
        AdminPermission.inventoryManage,
        AdminPermission.systemAdmin,
        AdminPermission.couponsManage,
      };

  AdminNavGroup catalogueFor(Set<AdminPermission> permissions) {
    return AdminNav.groupsFor(permissions)
        .firstWhere((group) => group.title == 'Catalogue');
  }

  group('The merchant can reach every selling mode', () {
    test('Visit to Buy and Services at Shop are in the Catalogue group', () {
      final labels =
          catalogueFor(merchant()).destinations.map((d) => d.label).toList();

      expect(labels, contains('Visit to Buy'),
          reason: 'this is the entry whose absence made the whole feature '
              'unreachable for a real merchant');
      expect(labels, contains('Services at Shop'));
      expect(labels, contains('Products'),
          reason: 'the existing entries must survive the addition');
    });

    test('they sit beside Products, where a merchant looks for what they sell', () {
      final labels =
          catalogueFor(merchant()).destinations.map((d) => d.label).toList();

      expect(labels.indexOf('Visit to Buy'), labels.indexOf('Products') + 1);
      expect(labels.indexOf('Services at Shop'), labels.indexOf('Visit to Buy') + 1);
    });

    test('the whole requested Catalogue order is what a merchant sees', () {
      expect(
          catalogueFor(merchant()).destinations.map((d) => d.label).toList(),
          ['Products', 'Visit to Buy', 'Services at Shop', 'Categories',
            'Inventory', 'Import Catalogue', 'Coupons']);
    });

    test('a merchant who may only VIEW the catalogue still sees both', () {
      // catalogView, not catalogManage: an account that can read the shelf
      // should be able to read these two parts of it as well.
      final viewer = {AdminPermission.catalogView};
      final labels =
          catalogueFor(viewer).destinations.map((d) => d.label).toList();

      expect(labels, contains('Visit to Buy'));
      expect(labels, contains('Services at Shop'));
    });

    test('each entry builds the screen for its own mode', () {
      final catalogue = catalogueFor(merchant());

      final visit = catalogue.destinations
          .firstWhere((d) => d.label == 'Visit to Buy')
          .builder(_context);
      final services = catalogue.destinations
          .firstWhere((d) => d.label == 'Services at Shop')
          .builder(_context);

      expect(visit, isA<MerchantModeCatalogueScreen>());
      expect(services, isA<MerchantModeCatalogueScreen>());
      // The two entries must not open the same screen - that would look
      // wired up and show a jeweller their haircuts.
      expect((visit as MerchantModeCatalogueScreen).mode,
          isNot((services as MerchantModeCatalogueScreen).mode));
    });

    test('both are reachable by id, so a saved selection restores', () {
      expect(AdminNav.byId('visit-to-buy').label, 'Visit to Buy');
      expect(AdminNav.byId('services-at-shop').label, 'Services at Shop');
    });
  });

  group('Super Admin does not inherit merchant catalogue entries', () {
    test('the platform console has no Visit to Buy destination', () {
      // Super Admin inspects shops through search and drill-down; it does not
      // assume a merchant identity, which is why it has no Switch Shop either.
      final labels = AdminNav.groupsFor({AdminPermission.platformAdmin})
          .expand((group) => group.destinations)
          .map((d) => d.label)
          .toList();

      expect(labels, isNot(contains('Visit to Buy')));
      expect(labels, isNot(contains('Services at Shop')));
      expect(labels, isNot(contains('Switch Shop')));
    });
  });
}

/// The builders take a BuildContext and ignore it - they return a const
/// widget - so a throwaway element is enough to call them.
final BuildContext _context = _FakeContext();

class _FakeContext extends StatelessElement {
  _FakeContext() : super(const _Nothing());
}

class _Nothing extends StatelessWidget {
  const _Nothing();

  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}
