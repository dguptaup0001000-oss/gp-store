import 'package:flutter/foundation.dart';
import 'package:speech_to_text/speech_to_text.dart';

/// The recogniser, behind a seam.
///
/// WHY THIS EXISTS. SpeechToText is a concrete class that talks to a platform
/// channel, so nothing that uses it directly can be tested: not "permission
/// was refused", not "the device has no recogniser", not "listening ended
/// without hearing anything", and not the race that made voice search fail on
/// every device. Those are exactly the paths that matter, and they are all
/// unreachable from a unit test without a seam like this one.
///
/// It is deliberately thin - it adds no behaviour, so there is nothing here
/// that can be right in the fake and wrong in production.
abstract class SpeechEngine {
  Future<bool> initialize({
    required void Function(String status) onStatus,
    required void Function(String errorCode, bool permanent) onError,
  });

  Future<void> listen({
    required void Function(String words, bool isFinal) onResult,
    required String localeId,
    required Duration pauseFor,
    required Duration listenFor,
  });

  Future<void> stop();

  Future<void> cancel();

  bool get isListening;

  Future<bool> get hasPermission;
}

/// The real one.
class PluginSpeechEngine implements SpeechEngine {
  PluginSpeechEngine({SpeechToText? plugin}) : _plugin = plugin ?? SpeechToText();

  final SpeechToText _plugin;

  @override
  Future<bool> initialize({
    required void Function(String status) onStatus,
    required void Function(String errorCode, bool permanent) onError,
  }) {
    return _plugin.initialize(
      onStatus: onStatus,
      onError: (error) => onError(error.errorMsg, error.permanent),
      // The plugin's own debug logging prints the transcript. Off.
      debugLogging: false,
    );
  }

  @override
  Future<void> listen({
    required void Function(String words, bool isFinal) onResult,
    required String localeId,
    required Duration pauseFor,
    required Duration listenFor,
  }) {
    return _plugin.listen(
      onResult: (result) => onResult(result.recognizedWords, result.finalResult),
      // EVERYTHING goes in listenOptions. The same settings also exist as
      // top-level arguments and every one of them is deprecated in 7.x.
      listenOptions: SpeechListenOptions(
        localeId: localeId,
        pauseFor: pauseFor,
        listenFor: listenFor,
        partialResults: true,
        // Dictation rather than confirmation: the customer is reading out a
        // shopping list, not answering yes or no.
        listenMode: ListenMode.dictation,
        cancelOnError: true,
        // On-device only would fail outright on a phone with no offline Hindi
        // pack, which is most of them.
        onDevice: false,
      ),
    );
  }

  @override
  Future<void> stop() => _plugin.stop();

  @override
  Future<void> cancel() => _plugin.cancel();

  @override
  bool get isListening => _plugin.isListening;

  @override
  Future<bool> get hasPermission => _plugin.hasPermission;
}

/// Status strings the plugin reports. Named here so the service reads as
/// intent rather than as string comparison.
class SpeechStatus {
  const SpeechStatus._();

  static const listening = 'listening';
  static const notListening = 'notListening';
  static const done = 'done';

  /// Whether this status means the recogniser has stopped for good.
  static bool isTerminal(String status) => status == done || status == notListening;
}

/// Error codes the Android recogniser reports, grouped by what a customer can
/// do about them.
///
/// The plugin passes these through from the platform, so they are Android's
/// names rather than ours.
class SpeechErrors {
  const SpeechErrors._();

  static const permission = 'error_permission';
  static const noMatch = 'error_no_match';
  static const speechTimeout = 'error_speech_timeout';
  static const network = 'error_network';
  static const networkTimeout = 'error_network_timeout';
  static const busy = 'error_busy';
  static const audio = 'error_audio';

  /// "I heard nothing useful" - not a fault, and worth a different message
  /// from a fault.
  static bool isSilence(String code) => code == noMatch || code == speechTimeout;

  static bool isPermission(String code) => code == permission;

  static bool isNetwork(String code) => code == network || code == networkTimeout;

  static bool isBusy(String code) => code == busy;
}

@visibleForTesting
typedef SpeechEngineFactory = SpeechEngine Function();
