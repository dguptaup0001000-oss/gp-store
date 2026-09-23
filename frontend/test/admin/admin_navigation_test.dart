import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/shell/admin_destinations.dart';

/// The navigation is now the ONLY way into seventeen admin screens. The old
/// card-list home screen is gone, so a destination missing from this list is
/// a feature that has silently disappeared from the app - no compile error,
/// no crash, just a screen nobody can reach any more.
void main() {
  // These are exactly the tiles the old AdminHomeScreen showed. If a rename
  // drops one, the first test fails rather than shipping an app that quietly
  // lost a feature. Hoisted out of that test so the second one can measure
  // what has been added since.
  const expected = {
    'Dashboard',
    'Orders',
    'Payments',
    'Delivery Breaches',
    'Products',
    'Categories',
    'Inventory',
    'Coupons',
    // 'Delivery Partners' was here. It is not a screen that went missing: it
    // was REPLACED by 'Delivery Workers' (in addedSince below), which does
    // the same job and also owns the rider's app login. Two roster screens
    // where one of them could set a worker's credentials and the other could
    // not is what made worker sign-in so hard to get right.
    'Territories',
    'Delivery Pricing',
    'Customers',
    'Reviews',
    'Broadcast',
    'Analytics',
    'Audit Log',
    'Order Announcements',
    'Receipt Printer',
  };

  test('every screen the console used to list is still reachable', () {
    final labels = AdminNav.groups
        .expand((group) => group.destinations)
        .map((d) => d.label)
        .toSet();
    // containsAll, not equality. This set is the OLD home screen's tiles, and
    // its job is that none of them vanished - so a destination added since
    // then is not a failure of this test. Equality only happened to work
    // while the two sets coincided, and turned the first new screen into a
    // failure that said "Store Hours" where it meant "nothing is missing".
    expect(labels, containsAll(expected));
  });

  test('a destination added since that list is declared here on purpose', () {
    // The other half of the guarantee above. containsAll would let a screen
    // be added with nobody noticing, so every destination beyond the original
    // tiles is named here - adding one to the sidebar without adding it to
    // this list fails, which is the point.
    const addedSince = {
      'Packing List',
      'Store Hours',
      'Delivery Workers',
      // Items customers have sent back, waiting on a decision. Behind
      // ordersView: seeing the queue is an operations question, while the
      // approve button inside it is gated on the refund permission by the
      // backend, so a staff member who may look cannot pay.
      'Returns',
      // Bulk catalogue upload. SYSTEM_ADMIN only, matching SecurityConfig's
      // gate on /api/admin/catalog/**: one spreadsheet can rewrite every
      // price in the shop, so it is not opened to the catalogue roles.
      'Import Catalogue',
      // What this shop TOOK, and in what form - distinct from Analytics,
      // which is about what sold. Cash still in a rider's pocket is not the
      // same fact as a sale. Behind analyticsView, matching SecurityConfig's
      // gate on /api/shop/earnings.
      'Earnings',
      // Why this shop is or is not selling: its status, the steps that still
      // block orders, and what is waiting to be packed. Behind catalogView,
      // the widest permission /api/shop/** admits.
      'My Shop',
      // RUNNING THE MARKETPLACE, not running a shop. platformAdmin only,
      // which no shop role holds - see admin_permissions_test. It is in this
      // console rather than a fifth app because the platform operator and the
      // shopkeeper use the same screens for orders, customers and audit.
      //
      // The two ways to sell that are not "post it and ship it". Both sit in
      // Catalogue immediately after Products, and both are behind catalogView
      // like the rest of that group - a merchant who may edit their shelf may
      // see what is on it. They were the whole point of the commerce_mode
      // work and for a release they existed only as a field buried in the
      // variant form, which is why merchant_can_reach_every_mode_test now
      // asserts their position in the drawer and not just their existence.
      'Visit to Buy',
      'Services at Shop',
      // Safe installed-APK and backend identity used to distinguish a real
      // release from a stale/corrupt sideload during phone acceptance.
      'Release Diagnostics',
    };

    final labels = AdminNav.groups
        .expand((group) => group.destinations)
        .map((d) => d.label)
        .toSet();
    expect(labels.difference(expected), addedSince);
  });

  test('the platform owner gets a separate control-tower navigation', () {
    final labels = AdminNav.superAdminGroups
        .expand((group) => group.destinations)
        .map((d) => d.label)
        .toSet();

    expect(labels, {
      'Control Tower',
      'Merchants',
      'Shops',
      'Customers',
      'Merchant Administration',
      'Orders',
      'Workers',
      'Products',
      'Finance',
      'Payments',
      'Refunds',
      'Returns',
      'Product Reviews',
      'Shop Reviews',
      'Security',
      'Audit Logs',
      'System Health',
      'Release Diagnostics',
    });
    expect(labels, isNot(contains('My Shop')));
    expect(labels, isNot(contains('Store Hours')));
    expect(labels, isNot(contains('Receipt Printer')));
  });

  test('ids are unique - they are the selection key', () {
    final ids = AdminNav.all.map((d) => d.id).toList();
    expect(ids.toSet().length, ids.length);
  });

  test('the dashboard is the first destination and the default selection', () {
    expect(AdminNav.all.first.id, AdminNav.dashboardId);
    expect(AdminNav.byId(AdminNav.dashboardId).label, 'Dashboard');
  });

  test('an unknown id lands on the dashboard rather than throwing', () {
    // A selection id can outlive the destination that produced it. Landing
    // somewhere sensible is recoverable; a crash on launch is not.
    expect(AdminNav.byId('a-screen-that-was-deleted').id, AdminNav.dashboardId);
    expect(AdminNav.byId('').id, AdminNav.dashboardId);
  });

  test('every group has a title and at least one destination', () {
    for (final group in AdminNav.groups) {
      expect(group.title, isNotEmpty);
      expect(group.destinations, isNotEmpty);
    }
  });
}
