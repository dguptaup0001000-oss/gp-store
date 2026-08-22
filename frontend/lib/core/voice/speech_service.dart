import 'package:flutter/foundation.dart';
import 'package:speech_to_text/speech_to_text.dart';

/// What happened when the customer tapped the microphone.
enum VoiceOutcome {
  /// A transcript came back. It may still be nonsense - that is the parser's
  /// problem, not this class's.
  heard,

  /// The customer denied the microphone, or has denied it permanently.
  permissionDenied,

  /// No recogniser on this device, or the platform refused to start one.
  unavailable,

  /// Listening worked and produced nothing - silence, or noise.
  noSpeech,

  /// The customer stopped it themselves before saying anything.
  cancelled,

  /// Anything else. Never surfaced as a stack trace.
  failed,
}

class VoiceResult {
  const VoiceResult(this.outcome, {this.transcript = ''});

  final VoiceOutcome outcome;
  final String transcript;

  bool get hasText => transcript.trim().isNotEmpty;
}

/// The microphone, wrapped once.
///
/// WHAT THIS DELIBERATELY IS NOT. It is not a speech engine, and this project
/// does not own one. It drives the recogniser already on the phone - the same
/// one behind the keyboard's microphone key - which is why Hindi, Hinglish
/// and Indian-accented English work at all without a paid API or a shipped
/// model. It also means recognition quality is the device's, and on a very
/// old or stripped-down Android it may simply be absent; [VoiceOutcome] has a
/// case for that rather than pretending otherwise.
///
/// PRIVACY. Audio never leaves the recogniser. This class receives text and
/// nothing else - there is no recording, no file, no upload and nothing to
/// delete afterwards. The only thing it holds is the transcript, in memory,
/// for as long as the search screen is showing it.
///
/// FINAL RESULTS ONLY REACH THE NETWORK. Partial results are requested so the
/// sheet can show words appearing as they are spoken, but a SEARCH is only
/// ever issued from the final transcript. Searching on partials would put a
/// request on the wire for every syllable of every sentence, which is the one
/// thing a shop on a village connection cannot afford.
class SpeechService {
  SpeechService({SpeechToText? engine}) : _engine = engine ?? SpeechToText();

  final SpeechToText _engine;

  bool _initialised = false;
  bool _available = false;

  /// Indian Hindi first.
  ///
  /// This single choice does much of the work the brief asks for. hi-IN on
  /// Android is not a Hindi-only mode - it is trained on the code-switching
  /// people actually speak, so "Amul ka doodh" and "do packet Parle-G" come
  /// back with the English brand names intact. Setting en-IN instead would
  /// transcribe Hindi words phonetically as English, which is precisely the
  /// input the rest of the pipeline is worst at.
  static const String preferredLocale = 'hi_IN';

  /// How long to keep listening with nobody speaking before giving up. Long
  /// enough for somebody deciding what to buy, short enough that a phone in a
  /// pocket does not sit with its microphone open.
  static const Duration pauseFor = Duration(seconds: 3);

  /// Hard ceiling on one utterance, so a stuck recogniser cannot listen
  /// forever.
  static const Duration listenFor = Duration(seconds: 15);

  /// Polling interval while waiting for the recogniser to finish.
  static const Duration _settleTick = Duration(milliseconds: 100);

  bool get isListening => _engine.isListening;

  /// True once [prepare] has found a working recogniser.
  bool get isAvailable => _available;

  /// Initialises the plugin, which also triggers the microphone permission
  /// prompt on Android.
  ///
  /// Safe to call repeatedly: the plugin is initialised once and the answer
  /// remembered, so tapping the microphone five times does not ask five
  /// times.
  Future<bool> prepare() async {
    if (_initialised) return _available;

    try {
      _available = await _engine.initialize(
        onError: (error) => debugPrint('Speech error: ${error.errorMsg}'),
        onStatus: (status) => debugPrint('Speech status: $status'),
        // The plugin's own debug logging prints the transcript. Off.
        debugLogging: false,
      );
    } catch (e) {
      debugPrint('Speech init failed: $e');
      _available = false;
    }

    _initialised = true;
    return _available;
  }

  /// Listens once and returns what was heard.
  ///
  /// [onPartial] is for display only - see the class doc on why a search is
  /// never issued from it.
  Future<VoiceResult> listenOnce({ValueChanged<String>? onPartial}) async {
    final ready = await prepare();
    if (!ready) {
      // The plugin does not distinguish "no recogniser" from "permission
      // refused" in its return value, and the difference matters to the
      // customer: one is fixable in Settings, the other is not fixable at
      // all. hasPermission is what separates them.
      final permitted = await _hasPermission();
      return VoiceResult(
        permitted ? VoiceOutcome.unavailable : VoiceOutcome.permissionDenied,
      );
    }

    final transcript = StringBuffer();
    var sawFinal = false;

    try {
      await _engine.listen(
        onResult: (result) {
          if (result.finalResult) {
            sawFinal = true;
            transcript
              ..clear()
              ..write(result.recognizedWords);
          } else {
            onPartial?.call(result.recognizedWords);
          }
        },
        // EVERYTHING goes in listenOptions. The same settings also exist as
        // top-level arguments to listen(), and every one of them is
        // deprecated in 7.x - passing them there works today and warns, and
        // is the shape that breaks at the next major version.
        listenOptions: SpeechListenOptions(
          localeId: preferredLocale,
          pauseFor: pauseFor,
          listenFor: listenFor,
          partialResults: true,
          // Dictation rather than confirmation: the customer is reading out a
          // shopping list, not answering yes/no. The plugin documents this as
          // iOS-only, so on Android it changes nothing - it is set because it
          // describes the intent correctly, not because it is doing work here.
          listenMode: ListenMode.dictation,
          cancelOnError: true,
          // Nothing about a grocery list is sensitive enough to justify
          // refusing the device's own recogniser, and on-device-only would
          // silently degrade Hindi on most phones - or fail outright on a
          // phone with no offline Hindi pack, which is most of them.
          onDevice: false,
        ),
      );
    } catch (e) {
      debugPrint('Speech listen failed: $e');
      return const VoiceResult(VoiceOutcome.failed);
    }

    // listen() returns as soon as the microphone OPENS, not when it closes,
    // so without this the transcript would be read while it is still empty.
    // Bounded by listenFor plus a margin: if the platform never clears its
    // listening flag, this must still return rather than spin forever.
    final deadline = DateTime.now().add(listenFor + const Duration(seconds: 5));
    while (_engine.isListening && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(_settleTick);
    }

    final text = transcript.toString().trim();
    if (text.isEmpty) {
      return VoiceResult(sawFinal ? VoiceOutcome.noSpeech : VoiceOutcome.cancelled);
    }
    return VoiceResult(VoiceOutcome.heard, transcript: text);
  }

  /// Stops listening and keeps whatever has been heard - the "I've finished
  /// talking" button.
  Future<void> stop() async {
    if (!_engine.isListening) return;
    try {
      await _engine.stop();
    } catch (e) {
      debugPrint('Speech stop failed: $e');
    }
  }

  /// Abandons the utterance entirely.
  Future<void> cancel() async {
    if (!_engine.isListening) return;
    try {
      await _engine.cancel();
    } catch (e) {
      debugPrint('Speech cancel failed: $e');
    }
  }

  Future<bool> _hasPermission() async {
    try {
      return await _engine.hasPermission;
    } catch (_) {
      return false;
    }
  }
}
