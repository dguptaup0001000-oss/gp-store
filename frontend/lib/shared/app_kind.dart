/// Which Android application this Dart isolate was compiled for.
///
/// Set with `--dart-define=GPSTORE_APP=customer` or `admin`. The value is
/// NOT authorization - the backend still 403s a customer JWT on
/// `/api/admin/**`. It only chooses token-storage prefix and copy so the
/// two APKs cannot share a session if a build ever reused an applicationId.
enum AppKind {
  customer,
  admin;

  static const String _raw =
      String.fromEnvironment('GPSTORE_APP', defaultValue: 'customer');

  static AppKind get current =>
      _raw == 'admin' ? AppKind.admin : AppKind.customer;

  static bool get isAdmin => current == AppKind.admin;

  static bool get isCustomer => current == AppKind.customer;

  static String get tokenKeyPrefix => isAdmin ? 'admin_' : '';
}
