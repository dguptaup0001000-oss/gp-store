import 'admin/admin_app.dart';
import 'core/storage/token_storage.dart';
import 'features/auth/presentation/auth_providers.dart';
import 'shared/bootstrap.dart';

/// GP-STORE Admin application.
///
///   flutter build apk --flavor admin -t lib/admin_main.dart \
///     --dart-define=GPSTORE_APP=admin
///
/// applicationId: in.gpstore.admin
/// Artifact: gpstore-admin-release.apk
///
/// Must not expose customer shopping. Backend RBAC still authorizes every
/// /api/admin/** call - this split is not a security boundary.
Future<void> main() => bootstrapGpstoreApp(
      app: const AdminApp(),
      overrides: [
        tokenStorageProvider.overrideWith(
          (ref) => TokenStorage(keyPrefix: 'admin_'),
        ),
      ],
    );
