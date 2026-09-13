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
/// call this - that APK must not require Firebase. The super admin APK
/// calls [bootstrapWithoutPush] below for the same reason.
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

/// Process start for an app that has no Firebase client, and needs none.
///
/// WHY A SECOND FUNCTION RATHER THAN A FLAG. The super admin APK's
/// applicationId is not in the Firebase project, so the google-services
/// Gradle plugin is not applied to that flavor (see android/app/build.gradle).
/// Calling [bootstrapGpstoreApp] there would reach
/// `Firebase.initializeApp()`, land in its catch block, and log a line that
/// reads like a misconfiguration every single launch. It is not one: push in
/// this project carries NEW_ORDER and ORDER_STATUS for a shop, and the
/// platform owner does not run a shop from this app.
///
/// [PushAvailability.firebaseReady] therefore stays false, which is already
/// the state every push call site checks - PushNotificationService.start()
/// returns immediately, and the notifications screen says push is off rather
/// than pretending otherwise.
///
/// Crash handlers are still installed. Losing push is a decision; losing
/// crash reports would just be an omission.
Future<void> bootstrapWithoutPush({
  required Widget app,
  List<Override> overrides = const [],
}) async {
  WidgetsFlutterBinding.ensureInitialized();
  AppEnvironment.assertReleaseBuildIsConfigured(isReleaseMode: kReleaseMode);

  PushAvailability.firebaseReady = false;
  await installCrashHandlers(const NoOpCrashReporter());

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
