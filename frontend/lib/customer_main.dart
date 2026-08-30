import 'customer/customer_app.dart';
import 'shared/bootstrap.dart';

/// GP-STORE Customer application.
///
///   flutter build apk --flavor customer -t lib/customer_main.dart \
///     --dart-define=GPSTORE_APP=customer
///
/// applicationId: in.gpstore.customer
/// Artifact: gpstore-customer-release.apk
///
/// Must not import admin screens. Backend RBAC still 403s customer JWTs
/// on /api/admin/** - this split is not a security boundary.
Future<void> main() => bootstrapGpstoreApp(app: const CustomerApp());
