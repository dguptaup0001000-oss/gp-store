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
    'Delivery Partners',
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
    final labels = AdminNav.all.map((d) => d.label).toSet();
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
    };

    final labels = AdminNav.all.map((d) => d.label).toSet();
    expect(labels.difference(expected), addedSince);
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
