/// Which Android application this Dart isolate was compiled for.
///
/// Set with `--dart-define=GPSTORE_APP=customer`, `admin` or `superadmin`.
/// The value is NOT authorization - the backend still 403s a customer JWT on
/// `/api/admin/**` and a merchant's JWT on `/api/platform/**`. It only
/// chooses token-storage prefix and copy so two APKs cannot share a session
/// if a build ever reused an applicationId.
enum AppKind {
  customer,
  admin,

  /// The platform owner's app. Same console, but its home screen is
  /// Merchants & Shops rather than one shop's dashboard.
  superAdmin;

  static const String _raw =
      String.fromEnvironment('GPSTORE_APP', defaultValue: 'customer');

  /// UNKNOWN FALLS BACK TO CUSTOMER, deliberately. A typo in a build command
  /// must produce the least-privileged copy, not the most.
  static AppKind get current => switch (_raw) {
        'admin' => AppKind.admin,
        'superadmin' => AppKind.superAdmin,
        _ => AppKind.customer,
      };

  static bool get isAdmin => current == AppKind.admin;

  static bool get isSuperAdmin => current == AppKind.superAdmin;

  static bool get isCustomer => current == AppKind.customer;

  /// Distinct per app, so a phone carrying both staff APKs keeps two
  /// separate sessions. Each already has its own applicationId and therefore
  /// its own storage sandbox; this is the second belt.
  static String get tokenKeyPrefix => prefixFor(current);

  /// The prefix for a given app, separate from [current] SO IT CAN BE TESTED.
  ///
  /// [current] is derived from a compile-time String.fromEnvironment, so one
  /// test process only ever sees one value of it. A test that wants to prove
  /// no two apps share a prefix - which is the property that matters - has to
  /// be able to ask about each one.
  static String prefixFor(AppKind kind) => switch (kind) {
        AppKind.admin => 'admin_',
        AppKind.superAdmin => 'superadmin_',
        AppKind.customer => '',
      };
}
