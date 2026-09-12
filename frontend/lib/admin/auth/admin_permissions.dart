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
  systemAdmin,

  /// Acts for the MARKETPLACE rather than for one shop. The only permission
  /// that grants a cross-shop scope, and no shop role holds it.
  platformAdmin,

  /// Writes the SHARED catalogue definition - what a product IS, as opposed
  /// to what this shop charges for it. Under a single-shop deployment the
  /// backend grants it to whoever holds catalogManage, because with one
  /// merchant the shopkeeper is the platform.
  catalogDefine,

  /// The MARKETPLACE's own numbers - /actuator/prometheus and
  /// /actuator/metrics, which report every shop's traffic at once. A merchant
  /// holding this could estimate platform-wide order volume from inside their
  /// own shop, so no shop role holds it: the platform owner does.
  platformObservability;

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

  /// A LEGACY ALIAS FOR [superAdmin], and not a role of its own.
  ///
  /// GP-STORE has four business roles: CUSTOMER, DELIVERY_BOY, ADMIN (a shop
  /// owner) and SUPER_ADMIN (the platform owner). This was once a third
  /// authority level - wider across shops, narrower inside one - and is not
  /// any more. It survives because `customers.role` is a string under a CHECK
  /// constraint that accepts it, so an account could still be carrying it.
  /// [_byRole] grants it exactly [superAdmin]'s set, and
  /// admin_permissions_test asserts that equality.
  static const platformAdmin = 'PLATFORM_ADMIN';

  /// Every permission a SHOP role can hold.
  ///
  /// Written as a subtraction rather than as `AdminPermission.values`, exactly
  /// as the backend's RolePermissions is: "everything in the enum" would mean
  /// a shopkeeper silently gaining whatever platform permission is added next.
  static final Set<AdminPermission> _all = Set.unmodifiable(
    AdminPermission.values.where(
      (p) =>
          p != AdminPermission.platformAdmin &&
          p != AdminPermission.catalogDefine &&
          // Every shop's traffic at once. See the enum member.
          p != AdminPermission.platformObservability,
    ),
  );

  /// Every permission there is: the platform owner's set, shared BY REFERENCE
  /// with the [platformAdmin] alias so the two cannot drift apart.
  ///
  /// `final`, not `const`, because it is built from AdminPermission.values and
  /// a const initializer cannot reference that. Nothing here mutates.
  static final Set<AdminPermission> _everything =
      Set.unmodifiable(AdminPermission.values);

  static final Map<String, Set<AdminPermission>> _byRole = {
    // THE PLATFORM OWNER, which is not the same thing as the largest shop
    // role. superAdmin used to be `_all` - byte-identical to admin - which
    // both handed the shopkeeper the marketplace's observability and withheld
    // the cross-shop scope from the person who owns the marketplace.
    superAdmin: _everything,
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
    // A LEGACY ALIAS FOR superAdmin, AND NOT A SECOND AUTHORITY.
    //
    // This was once its own authority level. The owner has since decided that
    // SUPER_ADMIN is the single highest role and PLATFORM_ADMIN is not a
    // separate business role, so it is granted the same set BY REFERENCE -
    // there is no second list here to forget to update either.
    platformAdmin: _everything,
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
