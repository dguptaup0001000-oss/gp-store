import 'customer_main.dart' as customer;

/// Default `flutter run` / `flutter test` entry. Production customer
/// artifacts are built from [customer_main.dart] with `--flavor customer`.
///
///   flutter run --flavor customer --dart-define=GPSTORE_APP=customer
///   flutter run --flavor admin -t lib/admin_main.dart --dart-define=GPSTORE_APP=admin
Future<void> main() => customer.main();
