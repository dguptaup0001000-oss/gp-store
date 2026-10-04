import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/config/app_environment.dart';
import '../core/monitoring/crash_reporter.dart';
import '../core/notifications/push_availability.dart';
import '../core/storage/token_storage.dart';
import '../features/auth/presentation/auth_providers.dart';
import 'app_kind.dart';

/// Starts the platform-control APK without Firebase, shop push, or printer
/// initialization.
Future<void> bootstrapPlatformAdmin({required Widget app}) async {
  WidgetsFlutterBinding.ensureInitialized();
  AppEnvironment.assertReleaseBuildIsConfigured(isReleaseMode: kReleaseMode);
  PushAvailability.firebaseReady = false;
  await installCrashHandlers(const NoOpCrashReporter());

  runApp(ProviderScope(
    overrides: [
      tokenStorageProvider.overrideWith(
        (ref) => TokenStorage(keyPrefix: AppKind.tokenKeyPrefix),
      ),
    ],
    child: app,
  ));
}
