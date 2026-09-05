import 'package:flutter/material.dart';

import '../auth/admin_permissions.dart';

import '../../features/admin/presentation/admin_analytics_screen.dart';
import '../../features/admin/presentation/admin_audit_log_screen.dart';
import '../../features/admin/presentation/admin_broadcast_screen.dart';
import '../../features/admin/presentation/admin_catalog_import_screen.dart';
import '../../features/admin/presentation/admin_category_list_screen.dart';
import '../../features/admin/presentation/admin_coupon_list_screen.dart';
import '../../features/admin/presentation/admin_customers_screen.dart';
import '../../features/admin/presentation/admin_delivery_breaches_screen.dart';
import '../../features/admin/presentation/admin_workers_screen.dart';
import '../../features/admin/presentation/admin_delivery_pricing_screen.dart';
import '../../features/admin/presentation/admin_inventory_screen.dart';
import '../../features/admin/presentation/admin_order_list_screen.dart';
import '../../features/admin/presentation/admin_payments_screen.dart';
import '../../features/admin/presentation/admin_printer_settings_screen.dart';
import '../../features/admin/presentation/admin_product_list_screen.dart';
import '../../features/admin/presentation/admin_returns_screen.dart';
import '../../features/admin/presentation/admin_reviews_screen.dart';
import '../../features/admin/presentation/admin_territories_screen.dart';
import '../../features/admin/presentation/admin_voice_settings_screen.dart';
import '../dashboard/admin_dashboard_screen.dart';
import '../operations/morning_preparation_screen.dart';
import '../operations/store_operations_screen.dart';

/// One place in the admin console you can navigate to.
///
/// THE BUILDER RETURNS THE EXISTING SCREEN, UNCHANGED. Restyling the console
/// is not a licence to rewrite seventeen working screens; every one of them
/// keeps its own state, providers and behaviour and is simply reached from a
/// sidebar instead of a list of cards. Converting them one at a time to
/// shell-native bodies is follow-up work, not a prerequisite for having a
/// shell.
@immutable
class AdminDestination {
  const AdminDestination({
    required this.id,
    required this.label,
    required this.icon,
    required this.builder,
    this.description,
    this.requires,
  });

  /// The permission this screen needs, or null for one everybody has.
  ///
  /// USED ONLY TO HIDE DEAD ENDS. The server refuses the underlying routes
  /// regardless; showing a SUPPORT account an Inventory link that can only
  /// ever return 403 is a worse experience than not showing it.
  final AdminPermission? requires;

  /// Stable identifier. Used as the selection key, so it must not change
  /// when a label is reworded.
  final String id;
  final String label;
  final IconData icon;

  /// One line for the drawer and for the old-style card list. Kept because
  /// "Coupons" alone does not tell a new staff member what the screen does.
  final String? description;

  final WidgetBuilder builder;
}

/// A labelled run of destinations in the sidebar.
///
/// Grouping is not decoration - it is how an operator finds a screen without
/// reading eighteen labels. The groups follow the shop's actual working day:
/// what is happening now (Operations), what is on the shelves (Catalogue),
/// who is delivering (Delivery), who is buying (Customers), and the
/// settings you touch once a month (System).
@immutable
class AdminNavGroup {
  const AdminNavGroup({required this.title, required this.destinations});

  final String title;
  final List<AdminDestination> destinations;
}

class AdminNav {
  const AdminNav._();

  static const String dashboardId = 'dashboard';

  static const AdminDestination dashboard = AdminDestination(
    id: dashboardId,
    label: 'Dashboard',
    icon: Icons.dashboard_outlined,
    description: 'Today at a glance',
    builder: _dashboard,
  );

  static Widget _dashboard(BuildContext context) => const AdminDashboardScreen();

  static Widget _preparation(BuildContext context) =>
      const MorningPreparationScreen();

  static Widget _storeHours(BuildContext context) =>
      const StoreOperationsScreen();

  static const List<AdminNavGroup> groups = [
    AdminNavGroup(
      title: 'Overview',
      destinations: [dashboard],
    ),
    AdminNavGroup(
      title: 'Operations',
      destinations: [
        AdminDestination(
          id: 'orders',
          requires: AdminPermission.ordersView,
          label: 'Orders',
          icon: Icons.receipt_long_outlined,
          description: 'View and manage every order',
          builder: _orders,
        ),
        // Both sit in Operations because that is when they are used: the
        // packing list first thing in the morning, the hours when something
        // has gone wrong. They are separate destinations because the people
        // differ - packing needs ORDERS_VIEW, and the switch that stops the
        // shop trading needs DELIVERY_MANAGE.
        AdminDestination(
          id: 'preparation',
          requires: AdminPermission.ordersView,
          label: 'Packing List',
          icon: Icons.checklist_outlined,
          description: "What to pack for the next delivery run",
          builder: _preparation,
        ),
        AdminDestination(
          id: 'store-hours',
          requires: AdminPermission.deliveryManage,
          label: 'Store Hours',
          icon: Icons.schedule_outlined,
          description: 'Pause orders, close a day',
          builder: _storeHours,
        ),
        AdminDestination(
          id: 'payments',
          requires: AdminPermission.paymentsView,
          label: 'Payments',
          icon: Icons.payments_outlined,
          description: 'Confirm UPI, process refunds',
          builder: _payments,
        ),
        AdminDestination(
          id: 'returns',
          // ordersView, not paymentsRefund: this screen is the QUEUE. Seeing
          // what customers have sent back is an operations question. The
          // approve button behind it is the one that moves money, and the
          // backend gates that separately on the refund permission - so a
          // staff member who may look cannot pay.
          requires: AdminPermission.ordersView,
          label: 'Returns',
          icon: Icons.assignment_return_outlined,
          description: 'Items customers have sent back',
          builder: _returns,
        ),
        AdminDestination(
          id: 'breaches',
          requires: AdminPermission.deliveryView,
          label: 'Delivery Breaches',
          icon: Icons.report_problem_outlined,
          description: 'Orders that missed their promised time',
          builder: _breaches,
        ),
      ],
    ),
    AdminNavGroup(
      title: 'Catalogue',
      destinations: [
        AdminDestination(
          id: 'products',
          requires: AdminPermission.catalogView,
          label: 'Products',
          icon: Icons.inventory_2_outlined,
          description: 'Add, edit, and manage stock',
          builder: _products,
        ),
        AdminDestination(
          id: 'categories',
          requires: AdminPermission.catalogManage,
          label: 'Categories',
          icon: Icons.category_outlined,
          description: 'Organise your product catalogue',
          builder: _categories,
        ),
        AdminDestination(
          id: 'inventory',
          requires: AdminPermission.inventoryManage,
          label: 'Inventory',
          icon: Icons.warehouse_outlined,
          description: 'Stock levels, restock, low-stock alerts',
          builder: _inventory,
        ),
        // SYSTEM_ADMIN, not catalogManage, and deliberately so: one upload can
        // rewrite every price in the shop, and SecurityConfig gates
        // /api/admin/catalog/** on SYSTEM_ADMIN. Listing it any wider here
        // would only show an INVENTORY_MANAGER a link that returns 403.
        AdminDestination(
          id: 'catalog-import',
          requires: AdminPermission.systemAdmin,
          label: 'Import Catalogue',
          icon: Icons.upload_file_outlined,
          description: 'Load products from a spreadsheet',
          builder: _catalogImport,
        ),
        AdminDestination(
          id: 'coupons',
          requires: AdminPermission.couponsManage,
          label: 'Coupons',
          icon: Icons.local_offer_outlined,
          description: 'Create and manage discount offers',
          builder: _coupons,
        ),
      ],
    ),
    AdminNavGroup(
      title: 'Delivery',
      destinations: [
        AdminDestination(
          id: 'workers',
          requires: AdminPermission.deliveryManage,
          label: 'Delivery Workers',
          icon: Icons.badge_outlined,
          description: 'Hire, pause and remove riders, and set their app login',
          builder: _workers,
        ),
        AdminDestination(
          id: 'territories',
          requires: AdminPermission.deliveryManage,
          label: 'Territories',
          icon: Icons.map_outlined,
          description: 'Zones, riders, and map outlines',
          builder: _territories,
        ),
        AdminDestination(
          id: 'delivery-pricing',
          requires: AdminPermission.deliveryManage,
          label: 'Delivery Pricing',
          icon: Icons.local_shipping_outlined,
          description: 'Distance, weight, and free-delivery rules',
          builder: _deliveryPricing,
        ),
      ],
    ),
    AdminNavGroup(
      title: 'Customers',
      destinations: [
        AdminDestination(
          id: 'customers',
          requires: AdminPermission.customersView,
          label: 'Customers',
          icon: Icons.people_outline,
          description: 'View and manage customer accounts',
          builder: _customers,
        ),
        AdminDestination(
          id: 'reviews',
          requires: AdminPermission.reviewsModerate,
          label: 'Reviews',
          icon: Icons.rate_review_outlined,
          description: 'Remove inappropriate reviews',
          builder: _reviews,
        ),
        AdminDestination(
          id: 'broadcast',
          requires: AdminPermission.broadcastSend,
          label: 'Broadcast',
          icon: Icons.campaign_outlined,
          description: 'Send an announcement to every customer',
          builder: _broadcast,
        ),
      ],
    ),
    AdminNavGroup(
      title: 'Insights',
      destinations: [
        AdminDestination(
          id: 'analytics',
          requires: AdminPermission.analyticsView,
          label: 'Analytics',
          icon: Icons.analytics_outlined,
          description: 'Sales, top products, order status',
          builder: _analytics,
        ),
        AdminDestination(
          id: 'audit',
          requires: AdminPermission.auditView,
          label: 'Audit Log',
          icon: Icons.history_outlined,
          description: 'Full history of important actions',
          builder: _audit,
        ),
      ],
    ),
    AdminNavGroup(
      title: 'System',
      destinations: [
        AdminDestination(
          id: 'announcements',
          label: 'Order Announcements',
          icon: Icons.record_voice_over_outlined,
          description: 'Speak new orders aloud, like a soundbox',
          builder: _announcements,
        ),
        AdminDestination(
          id: 'printer',
          label: 'Receipt Printer',
          icon: Icons.print_outlined,
          description: 'Connect a printer to auto-print new orders',
          builder: _printer,
        ),
      ],
    ),
  ];

  /// Flat list, in sidebar order. Used by the drawer and by lookups.
  static List<AdminDestination> get all =>
      [for (final group in groups) ...group.destinations];

  /// The groups this role may actually use, with empty groups dropped.
  ///
  /// A group whose every destination is hidden would otherwise render as a
  /// heading with nothing under it - which reads as a screen that failed to
  /// load rather than one that does not apply.
  static List<AdminNavGroup> groupsFor(Set<AdminPermission> permissions) {
    final visible = <AdminNavGroup>[];
    for (final group in groups) {
      final allowed = group.destinations
          .where((d) => d.requires == null || permissions.contains(d.requires))
          .toList();
      if (allowed.isNotEmpty) {
        visible.add(AdminNavGroup(title: group.title, destinations: allowed));
      }
    }
    return visible;
  }

  /// Whether this role may open that destination. The shell checks this
  /// before restoring a selection, so an id saved under a wider role cannot
  /// put a screen on screen that the account can no longer use.
  static bool isVisible(AdminDestination destination,
      Set<AdminPermission> permissions) {
    return destination.requires == null ||
        permissions.contains(destination.requires);
  }

  /// Falls back to the dashboard rather than throwing. A selection id can
  /// outlive the destination that produced it (a saved id, a deep link from
  /// an older build); landing on the dashboard is recoverable, a crash on
  /// launch is not.
  static AdminDestination byId(String id) {
    for (final group in groups) {
      for (final destination in group.destinations) {
        if (destination.id == id) return destination;
      }
    }
    return dashboard;
  }

  // Top-level functions, not closures: a const AdminDestination cannot hold
  // a lambda, and const destinations are what let the whole nav tree be a
  // compile-time constant rather than rebuilt on every frame.
  static Widget _orders(BuildContext context) => const AdminOrderListScreen();
  static Widget _payments(BuildContext context) => const AdminPaymentsScreen();
  static Widget _breaches(BuildContext context) =>
      const AdminDeliveryBreachesScreen();
  static Widget _products(BuildContext context) =>
      const AdminProductListScreen();
  static Widget _categories(BuildContext context) =>
      const AdminCategoryListScreen();
  static Widget _catalogImport(BuildContext context) =>
      const AdminCatalogImportScreen();
  static Widget _inventory(BuildContext context) => const AdminInventoryScreen();
  static Widget _coupons(BuildContext context) => const AdminCouponListScreen();
  static Widget _workers(BuildContext context) => const AdminWorkersScreen();
  static Widget _territories(BuildContext context) =>
      const AdminTerritoriesScreen();
  static Widget _deliveryPricing(BuildContext context) =>
      const AdminDeliveryPricingScreen();
  static Widget _customers(BuildContext context) => const AdminCustomersScreen();

  static Widget _returns(BuildContext context) => const AdminReturnsScreen();
  static Widget _reviews(BuildContext context) => const AdminReviewsScreen();
  static Widget _broadcast(BuildContext context) => const AdminBroadcastScreen();
  static Widget _analytics(BuildContext context) => const AdminAnalyticsScreen();
  static Widget _audit(BuildContext context) => const AdminAuditLogScreen();
  static Widget _announcements(BuildContext context) =>
      const AdminVoiceSettingsScreen();
  static Widget _printer(BuildContext context) =>
      const AdminPrinterSettingsScreen();
}
