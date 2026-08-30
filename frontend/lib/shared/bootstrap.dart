import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/config/app_environment.dart';
import '../core/logging/app_log.dart';
import '../core/monitoring/crash_reporter.dart';
import '../core/monitoring/firebase_crash_reporter.dart';
import '../core/notifications/push_availability.dart';
import '../core/notifications/push_notification_service.dart';
import '../core/storage/token_storage.dart';
import '../features/auth/presentation/auth_providers.dart';
import 'app_kind.dart';

/// Shared process start for the customer and admin APKs.
///
/// Worker has its own entrypoint (`lib/worker_main.dart`) and does not
/// call this - that APK must not require Firebase.
Future<void> bootstrapGpstoreApp({
  required Widget app,
  List<Override> overrides = const [],
}) async {
  WidgetsFlutterBinding.ensureInitialized();
  AppEnvironment.assertReleaseBuildIsConfigured(isReleaseMode: kReleaseMode);

  try {
    await Firebase.initializeApp();
    PushAvailability.firebaseReady = true;

    await installCrashHandlers(const FirebaseCrashReporter());
    FirebaseMessaging.onBackgroundMessage(firebaseBackgroundMessageHandler);
  } catch (e) {
    appLog(
        'Firebase not configured yet - push and crash reporting disabled: $e');
    PushAvailability.firebaseReady = false;
    await installCrashHandlers(const NoOpCrashReporter());
  }

  runApp(ProviderScope(
    overrides: [
      tokenStorageProvider.overrideWith(
        (ref) => TokenStorage(keyPrefix: AppKind.tokenKeyPrefix),
      ),
      ...overrides,
    ],
    child: app,
  ));
}
