import 'package:flutter/foundation.dart';

import '../config/app_environment.dart';

/// Diagnostic log that is silent in production release builds.
///
/// [debugPrint] still writes to logcat in release. Production must not.
void appLog(String message) {
  if (kDebugMode || AppEnvironment.current.verboseLogging) {
    debugPrint(message);
  }
}
