import 'admin/super_admin_app.dart';
import 'shared/bootstrap.dart';

/// GP-STORE Super Admin application - the platform owner's console.
///
///   flutter build apk --release --split-per-abi \
///     --target-platform android-arm,android-arm64 \
///     --flavor superadmin -t lib/super_admin_main.dart \
///     --dart-define=GPSTORE_APP=superadmin
///
/// applicationId: in.gpstore.superadmin
/// Artifacts: gpstore-superadmin-release.apk (arm64), -armv7.apk
///
/// WHY IT IS A SEPARATE APK. Registering a merchant, opening their shop and
/// handing over their one-time password is the platform owner's job and
/// nobody else's, and it was buried as the last group of a sidebar inside an
/// app whose home screen is one shop's trading day. This app opens on
/// Merchants & Shops.
///
/// WHAT THE SPLIT DOES NOT DO, stated as plainly as admin_main.dart states
/// it: this is not a security boundary. /api/platform/** is gated on
/// PERM_PLATFORM_ADMIN server-side - a permission RolePermissions builds
/// every shop role by SUBTRACTING - so a merchant who sideloads this APK is
/// refused by the backend on every screen in it, and a merchant who never
/// installs it is no less refused.
///
/// [bootstrapWithoutPush], not bootstrapGpstoreApp: this flavor has no
/// Firebase client and needs none. See that function for why.
Future<void> main() => bootstrapWithoutPush(app: const SuperAdminApp());
