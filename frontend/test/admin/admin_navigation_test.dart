import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/shell/admin_destinations.dart';

/// The navigation is now the ONLY way into seventeen admin screens. The old
/// card-list home screen is gone, so a destination missing from this list is
/// a feature that has silently disappeared from the app - no compile error,
/// no crash, just a screen nobody can reach any more.
void main() {
  test('every screen the console used to list is still reachable', () {
    // These are exactly the tiles the old AdminHomeScreen showed. If a
    // rename drops one, this fails rather than shipping an app that quietly
    // lost a feature.
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

    final labels = AdminNav.all.map((d) => d.label).toSet();
    expect(labels, expected);
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
