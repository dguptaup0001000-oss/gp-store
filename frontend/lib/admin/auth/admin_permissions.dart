/// The client's copy of the server's permission model.
///
/// THE SERVER IS THE AUTHORITY. Nothing here grants anything. SecurityConfig
/// gates every staff route on a PERM_ authority derived from the signed-in
/// account's live role, so a hand-built request from a SUPPORT account is
/// refused whether or not this file agrees. What this file is for is not
/// showing someone a screen that will only ever return 403 - a menu of dead
/// ends is a worse experience than a shorter menu.
///
/// KEPT IN STEP WITH THE BACKEND BY TEST, not by discipline. The names below
/// mirror backend AdminPermission and RolePermissions exactly, and
/// admin_permissions_test.dart reads those Java files and fails if the two
/// drift. Without that, the first permission added on the server would
/// silently hide a screen here, or worse, show one that does not work.
library;

enum AdminPermission {
  ordersView,
  ordersManage,
  paymentsView,
  paymentsManage,
  paymentsRefund,
  catalogView,
  catalogManage,
  inventoryManage,
  couponsManage,
  customersView,
  customersManage,
  deliveryView,
  deliveryManage,
  reviewsModerate,
  broadcastSend,
  analyticsView,
  auditView,
  systemAdmin;

  /// The backend enum constant this mirrors, e.g. ORDERS_VIEW.
  String get backendName =>
      name.replaceAllMapped(RegExp(r'[A-Z]'), (m) => '_${m[0]}').toUpperCase();
}

/// Backend Role values that are staff. CUSTOMER and DELIVERY_BOY are not.
class AdminRoles {
  const AdminRoles._();

  static const superAdmin = 'SUPER_ADMIN';
  static const admin = 'ADMIN';
  static const manager = 'MANAGER';
  static const inventoryManager = 'INVENTORY_MANAGER';
  static const orderManager = 'ORDER_MANAGER';
  static const deliveryManager = 'DELIVERY_MANAGER';
  static const support = 'SUPPORT';

  static final Set<AdminPermission> _all =
      Set.unmodifiable(AdminPermission.values);

  /// Mirrors backend RolePermissions. ADMIN holds everything - see that
  /// file for why that guarantee matters more than a tidy hierarchy.
  // `final`, not `const`: _all is built from AdminPermission.values, and a
  // const map cannot reference it. Nothing here mutates.
  static final Map<String, Set<AdminPermission>> _byRole = {
    superAdmin: _all,
    admin: _all,
    manager: {
      AdminPermission.ordersView,
      AdminPermission.ordersManage,
      AdminPermission.paymentsView,
      AdminPermission.paymentsManage,
      AdminPermission.paymentsRefund,
      AdminPermission.catalogView,
      AdminPermission.catalogManage,
      AdminPermission.inventoryManage,
      AdminPermission.couponsManage,
      AdminPermission.customersView,
      AdminPermission.customersManage,
      AdminPermission.deliveryView,
      AdminPermission.deliveryManage,
      AdminPermission.reviewsModerate,
      AdminPermission.broadcastSend,
      AdminPermission.analyticsView,
      AdminPermission.auditView,
    },
    inventoryManager: {
      AdminPermission.catalogView,
      AdminPermission.catalogManage,
      AdminPermission.inventoryManage,
      AdminPermission.analyticsView,
    },
    orderManager: {
      AdminPermission.ordersView,
      AdminPermission.ordersManage,
      AdminPermission.paymentsView,
      AdminPermission.paymentsManage,
      AdminPermission.catalogView,
      AdminPermission.customersView,
      AdminPermission.deliveryView,
    },
    deliveryManager: {
      AdminPermission.deliveryView,
      AdminPermission.deliveryManage,
      AdminPermission.ordersView,
      AdminPermission.customersView,
    },
    support: {
      AdminPermission.ordersView,
      AdminPermission.paymentsView,
      AdminPermission.catalogView,
      AdminPermission.customersView,
      AdminPermission.reviewsModerate,
    },
  };

  /// Permissions for a role name from the profile endpoint.
  ///
  /// FAILS CLOSED on anything unrecognised. A role this build has not heard
  /// of belongs to a newer backend; showing that person an empty console is
  /// recoverable, guessing what they may do is not.
  static Set<AdminPermission> permissionsFor(String? role) {
    if (role == null) return const <AdminPermission>{};
    return _byRole[role.trim().toUpperCase()] ?? const <AdminPermission>{};
  }

  /// Whether this role may use the admin app at all.
  static bool isStaff(String? role) => permissionsFor(role).isNotEmpty;

  /// Every staff role name, for tests and for the account label.
  static Iterable<String> get all => _byRole.keys;

  /// "INVENTORY_MANAGER" -> "Inventory Manager". A backend enum name must
  /// never reach an operator's screen verbatim.
  static String humanize(String? role) {
    if (role == null || role.isEmpty) return 'Staff';
    return role
        .split('_')
        .where((part) => part.isNotEmpty)
        .map((part) => part[0].toUpperCase() + part.substring(1).toLowerCase())
        .join(' ');
  }
}
