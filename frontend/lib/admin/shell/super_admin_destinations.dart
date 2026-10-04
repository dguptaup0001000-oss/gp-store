import 'package:flutter/material.dart';

import '../auth/admin_permissions.dart';
import '../../features/admin/presentation/platform_console_screen.dart';
import '../../features/admin/presentation/platform_control_tower_screen.dart';
import '../../features/admin/presentation/platform_directory_screen.dart';
import '../../features/admin/presentation/platform_finance_screen.dart';
import '../../features/admin/presentation/platform_resource_screen.dart';
import '../../features/admin/presentation/platform_system_health_screen.dart';
import '../../features/admin/presentation/admin_audit_log_screen.dart';
import '../../features/support/presentation/release_diagnostics_screen.dart';
import 'admin_navigation_model.dart';

/// Destinations reachable from the platform-owner app. This file deliberately
/// imports only platform screens; merchant order, printer, product-editing,
/// shop-switch, and checkout destinations stay in admin_destinations.dart.
class SuperAdminNav {
  const SuperAdminNav._();

  static const controlTower = AdminDestination(
    id: 'control-tower',
    requires: AdminPermission.platformAdmin,
    label: 'Control Tower',
    icon: Icons.space_dashboard_outlined,
    description: 'Marketplace KPIs and global search',
    builder: _controlTower,
  );

  static const platformConsole = AdminDestination(
    id: 'platform',
    requires: AdminPermission.platformAdmin,
    label: 'Merchant Administration',
    icon: Icons.hub_outlined,
    description: 'Approve, pause, suspend, and manage merchant access',
    builder: _platform,
  );

  static const releaseDiagnostics = AdminDestination(
    id: 'platform-release-diagnostics',
    requires: AdminPermission.platformAdmin,
    label: 'Release Diagnostics',
    icon: Icons.fact_check_outlined,
    description: 'APK, API, backend and database release identity',
    builder: _releaseDiagnostics,
  );

  static Widget _controlTower(BuildContext context) =>
      const PlatformControlTowerScreen();
  static Widget _platform(BuildContext context) => const PlatformConsoleScreen();
  static Widget _releaseDiagnostics(BuildContext context) =>
      const ReleaseDiagnosticsScreen();
  static Widget _merchants(BuildContext context) =>
      const PlatformDirectoryScreen(kind: DirectoryKind.merchants);
  static Widget _customers(BuildContext context) =>
      const PlatformDirectoryScreen(kind: DirectoryKind.customers);
  static Widget _resource(
    BuildContext context, {
    required String resource,
    required String title,
    required IconData icon,
  }) =>
      PlatformResourceScreen(resource: resource, title: title, icon: icon);
  static Widget _shops(BuildContext context) => _resource(
        context,
        resource: 'shops',
        title: 'Shops',
        icon: Icons.storefront_outlined,
      );
  static Widget _orders(BuildContext context) => _resource(
        context,
        resource: 'orders',
        title: 'Orders',
        icon: Icons.receipt_long_outlined,
      );
  static Widget _workers(BuildContext context) => _resource(
        context,
        resource: 'workers',
        title: 'Workers',
        icon: Icons.badge_outlined,
      );
  static Widget _products(BuildContext context) => _resource(
        context,
        resource: 'products',
        title: 'Products',
        icon: Icons.inventory_2_outlined,
      );
  static Widget _payments(BuildContext context) => _resource(
        context,
        resource: 'payments',
        title: 'Payments',
        icon: Icons.payments_outlined,
      );
  static Widget _refunds(BuildContext context) => _resource(
        context,
        resource: 'refunds',
        title: 'Refunds',
        icon: Icons.currency_rupee_outlined,
      );
  static Widget _returns(BuildContext context) => _resource(
        context,
        resource: 'returns',
        title: 'Returns',
        icon: Icons.assignment_return_outlined,
      );
  static Widget _reviews(BuildContext context) => _resource(
        context,
        resource: 'reviews',
        title: 'Product Reviews',
        icon: Icons.rate_review_outlined,
      );
  static Widget _shopReviews(BuildContext context) => _resource(
        context,
        resource: 'shop-reviews',
        title: 'Shop Reviews',
        icon: Icons.reviews_outlined,
      );
  static Widget _security(BuildContext context) => _resource(
        context,
        resource: 'security',
        title: 'Security Events',
        icon: Icons.security_outlined,
      );
  static Widget _audit(BuildContext context) => const AdminAuditLogScreen();
  static Widget _health(BuildContext context) =>
      const PlatformSystemHealthScreen();
  static Widget _finance(BuildContext context) => const PlatformFinanceScreen();

  static const List<AdminNavGroup> groups = [
    AdminNavGroup(title: 'Overview', destinations: [controlTower]),
    AdminNavGroup(title: 'Marketplace', destinations: [
      AdminDestination(id: 'platform-merchants', requires: AdminPermission.platformAdmin,
          label: 'Merchants', icon: Icons.business_outlined, builder: _merchants),
      AdminDestination(id: 'platform-shops', requires: AdminPermission.platformAdmin,
          label: 'Shops', icon: Icons.storefront_outlined, builder: _shops),
      AdminDestination(id: 'platform-customers', requires: AdminPermission.platformAdmin,
          label: 'Customers', icon: Icons.people_outline, builder: _customers),
      platformConsole,
    ]),
    AdminNavGroup(title: 'Commerce', destinations: [
      AdminDestination(id: 'platform-orders', requires: AdminPermission.platformAdmin,
          label: 'Orders', icon: Icons.receipt_long_outlined, builder: _orders),
      AdminDestination(id: 'platform-workers', requires: AdminPermission.platformAdmin,
          label: 'Workers', icon: Icons.badge_outlined, builder: _workers),
      AdminDestination(id: 'platform-products', requires: AdminPermission.platformAdmin,
          label: 'Products', icon: Icons.inventory_2_outlined, builder: _products),
    ]),
    AdminNavGroup(title: 'Money', destinations: [
      AdminDestination(id: 'platform-finance', requires: AdminPermission.platformAdmin,
          label: 'Finance', icon: Icons.account_balance_outlined, builder: _finance),
      AdminDestination(id: 'platform-payments', requires: AdminPermission.platformAdmin,
          label: 'Payments', icon: Icons.payments_outlined, builder: _payments),
      AdminDestination(id: 'platform-refunds', requires: AdminPermission.platformAdmin,
          label: 'Refunds', icon: Icons.currency_rupee_outlined, builder: _refunds),
      AdminDestination(id: 'platform-returns', requires: AdminPermission.platformAdmin,
          label: 'Returns', icon: Icons.assignment_return_outlined, builder: _returns),
    ]),
    AdminNavGroup(title: 'Trust & System', destinations: [
      AdminDestination(id: 'platform-reviews', requires: AdminPermission.platformAdmin,
          label: 'Product Reviews', icon: Icons.rate_review_outlined, builder: _reviews),
      AdminDestination(id: 'platform-shop-reviews', requires: AdminPermission.platformAdmin,
          label: 'Shop Reviews', icon: Icons.reviews_outlined, builder: _shopReviews),
      AdminDestination(id: 'platform-security', requires: AdminPermission.platformAdmin,
          label: 'Security', icon: Icons.security_outlined, builder: _security),
      AdminDestination(id: 'platform-audit', requires: AdminPermission.auditView,
          label: 'Audit Logs', icon: Icons.policy_outlined, builder: _audit),
      AdminDestination(id: 'platform-health', requires: AdminPermission.platformAdmin,
          label: 'System Health', icon: Icons.monitor_heart_outlined, builder: _health),
      releaseDiagnostics,
    ]),
  ];

  static const AdminNavigationCatalog navigation = AdminNavigationCatalog(
    dashboardId: 'no-dashboard',
    merchantGroups: [],
    platformGroups: groups,
    fallback: controlTower,
    dashboardBuilder: _controlTower,
  );
}
