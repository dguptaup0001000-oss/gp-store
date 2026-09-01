import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart';

import '../logging/app_log.dart';

/// Asks for POST_NOTIFICATIONS, at the moment the app is about to post one.
///
/// WHY THIS EXISTS SEPARATELY FROM PUSH. The customer app gets this prompt for
/// free, because FirebaseMessaging.requestPermission() raises it. The worker
/// app has no Firebase at all - it is a deliberately slim APK - and yet it is
/// the app that most needs the permission, because its location foreground
/// service must post an ongoing notification and on API 33+ that notification
/// is hidden without this grant. A rider would then be tracked with nothing on
/// screen telling them so, which is the exact outcome the notification exists
/// to prevent.
///
/// CALLED WHEN THE FEATURE STARTS, NEVER ON FIRST LAUNCH. A permission prompt
/// on a screen that has not yet explained itself is the one users deny, and a
/// denial is sticky. This is invoked as tracking begins, so the notification
/// it is asking about appears seconds later.
///
/// Below API 33 there is no such runtime permission and this resolves true
/// without prompting anybody.
Future<bool> requestNotificationPermission() async {
  if (defaultTargetPlatform != TargetPlatform.android &&
      defaultTargetPlatform != TargetPlatform.iOS) {
    return true;
  }
  try {
    final status = await Permission.notification.status;
    // isDenied is the only askable state. Asking again when it is permanently
    // denied returns immediately without a prompt, and asking when it is
    // already granted is a wasted round trip through the platform channel.
    if (status.isGranted || status.isLimited) {
      return true;
    }
    if (status.isPermanentlyDenied || status.isRestricted) {
      return false;
    }
    return (await Permission.notification.request()).isGranted;
  } catch (error) {
    // A missing plugin registration or a platform channel failure must never
    // propagate: every caller treats this as best-effort, and the feature it
    // guards works either way.
    appLog('POST_NOTIFICATIONS request failed: $error');
    return false;
  }
}
