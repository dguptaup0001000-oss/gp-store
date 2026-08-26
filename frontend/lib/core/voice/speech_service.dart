import 'dart:async';

import 'speech_engine.dart';
import '../logging/app_log.dart';

/// What happened when the customer tapped the microphone.
enum VoiceOutcome {
  /// A transcript came back. It may still be nonsense - that is the parser's
  /// problem, not this class's.
  heard,

  /// The customer refused the microphone, and can grant it next time.
  permissionDenied,

  /// Refused permanently, or refused through Android's "don't ask again".
  /// Only Settings can undo this, so the UI must offer that rather than a
  /// retry that cannot succeed.
  permissionPermanentlyDenied,

  /// No recogniser on this device, or the platform refused to start one.
  recognizerUnavailable,

  /// Listening worked and heard nothing - silence, or noise.
  noSpeech,

  /// The recogniser needs the network and could not reach it. NOT a product
  /// search failure, and not something a customer fixes by speaking louder.
  networkUnavailable,

  /// Another app holds the microphone.
  busy,

  /// The customer stopped it themselves.
  cancelled,

  /// Anything else. Never surfaced as a stack trace.
  failed,
}

class VoiceResult {
  const VoiceResult(this.outcome, {this.transcript = ''});

  final VoiceOutcome outcome;
  final String transcript;

  bool get hasText => transcript.trim().isNotEmpty;

  /// Whether offering "Try again" makes sense. Permission and a missing
  /// recogniser do not get better by trying again, and a button that cannot
  /// work is worse than no button.
  bool get isRetryable =>
      outcome == VoiceOutcome.noSpeech ||
      outcome == VoiceOutcome.failed ||
      outcome == VoiceOutcome.busy ||
      outcome == VoiceOutcome.networkUnavailable;
}

/// The microphone, wrapped once.
///
/// THE BUG THIS WAS REWRITTEN FOR. The first version started listening and
/// then waited like this:
///
///     while (engine.isListening) { await Future.delayed(100ms); }
///
/// which looks reasonable and never works. The plugin sets its listening flag
/// inside the STATUS CALLBACK, delivered asynchronously over the platform
/// channel some time after listen() returns. So the loop read false on its
/// first check, exited immediately, and reported an empty transcript - on
/// every device, every time. The microphone never got a chance to hear
/// anything, and the customer saw "something went wrong listening".
///
/// It is now driven by the recogniser's own callbacks: a session completes
/// when a final result arrives, when the recogniser reports a terminal
/// status, or when it reports an error - with a hard timeout as a backstop so
/// a platform that goes quiet cannot leave the sheet listening forever.
///
/// WHAT THIS DELIBERATELY IS NOT. It is not a speech engine, and this project
/// does not own one. It drives the recogniser already on the phone, which is
/// why Hindi, Hinglish and Indian-accented English work at all without a paid
/// API or a shipped model.
///
/// PRIVACY. Audio never leaves the recogniser. This class receives text and
/// nothing else - no recording, no file, no upload, nothing to delete.
class SpeechService {
  SpeechService({SpeechEngine? engine})
      : _engine = engine ?? PluginSpeechEngine();

  final SpeechEngine _engine;

  bool _initialised = false;
  bool _available = false;

  /// The listen in progress, if any. Non-null is the whole of "already
  /// listening" - see [listenOnce] on why a second tap must not start a
  /// second recogniser.
  _Session? _session;

  /// Indian Hindi first.
  ///
  /// hi-IN on Android is not a Hindi-only mode - it is trained on the
  /// code-switching people actually speak, so "Amul ka doodh" comes back with
  /// the English brand intact. en-IN would transcribe Hindi words
  /// phonetically as English, which is the input the rest of the pipeline is
  /// worst at.
  static const String preferredLocale = 'hi_IN';

  /// Silence that ends an utterance.
  static const Duration pauseFor = Duration(seconds: 3);

  /// Ceiling on one utterance.
  static const Duration listenFor = Duration(seconds: 15);

  /// Backstop. If the platform neither completes nor errors, this ends the
  /// session anyway rather than leaving the UI saying "Listening..." forever.
  static const Duration _hardStop = Duration(seconds: 20);

  bool get isListening => _session != null;

  bool get isAvailable => _available;

  /// Initialises the plugin, which also triggers the permission prompt.
  ///
  /// Safe to call repeatedly: initialised once, answer remembered, so five
  /// taps do not ask five times.
  Future<bool> prepare() async {
    if (_initialised) return _available;

    try {
      _available = await _engine.initialize(
        onStatus: _onStatus,
        onError: _onError,
      );
    } catch (e) {
      appLog('Speech init failed: $e');
      _available = false;
    }

    _initialised = true;
    return _available;
  }

  /// Listens once and returns what was heard.
  ///
  /// [onPartial] is for display only. A SEARCH is only ever issued from the
  /// final transcript - searching on partials would put a request on the wire
  /// for every syllable of every sentence.
  Future<VoiceResult> listenOnce({ValueChanged<String>? onPartial}) async {
    // A second tap while the first is still listening must not start a second
    // recogniser: two live sessions fight over one microphone and the loser
    // reports an error that looks like a fault. The existing session is
    // returned instead, so the second tap simply joins the first.
    final existing = _session;
    if (existing != null) return existing.future;

    final ready = await prepare();
    if (!ready) {
      // The plugin does not distinguish "no recogniser" from "permission
      // refused" in its return value, and the difference decides whether the
      // UI offers Settings or explains the device cannot do this at all.
      final permitted = await _hasPermission();
      return VoiceResult(
        permitted
            ? VoiceOutcome.recognizerUnavailable
            : VoiceOutcome.permissionDenied,
      );
    }

    final session = _Session(onPartial: onPartial);
    _session = session;

    try {
      await _engine.listen(
        onResult: session.onResult,
        localeId: preferredLocale,
        pauseFor: pauseFor,
        listenFor: listenFor,
      );
    } catch (e) {
      appLog('Speech listen failed: $e');
      _session = null;
      return const VoiceResult(VoiceOutcome.failed);
    }

    session.armBackstop(_hardStop);

    final result = await session.future;
    _session = null;
    return result;
  }

  void _onStatus(String status) {
    final session = _session;
    if (session == null) return;

    if (status == SpeechStatus.listening) {
      session.markStarted();
      return;
    }

    if (SpeechStatus.isTerminal(status)) {
      // Listening ended. Whatever was heard by now is the answer - and if
      // nothing was, that is silence rather than a fault, which is a
      // different message and a different button.
      session.completeFromTranscript();
    }
  }

  void _onError(String code, bool permanent) {
    final session = _session;
    if (session == null) return;

    if (SpeechErrors.isSilence(code)) {
      // Android reports "no match" as an error. It is not one - the customer
      // simply did not say anything the recogniser could use.
      session.completeFromTranscript();
      return;
    }

    if (SpeechErrors.isPermission(code)) {
      session.complete(VoiceResult(permanent
          ? VoiceOutcome.permissionPermanentlyDenied
          : VoiceOutcome.permissionDenied));
      return;
    }

    if (SpeechErrors.isNetwork(code)) {
      session.complete(const VoiceResult(VoiceOutcome.networkUnavailable));
      return;
    }

    if (SpeechErrors.isBusy(code)) {
      session.complete(const VoiceResult(VoiceOutcome.busy));
      return;
    }

    appLog('Speech error: $code');
    session.complete(const VoiceResult(VoiceOutcome.failed));
  }

  /// Stops listening and keeps whatever has been heard - the "I've finished
  /// talking" button.
  Future<void> stop() async {
    try {
      await _engine.stop();
    } catch (e) {
      appLog('Speech stop failed: $e');
    }
    // Do not wait for a status that may not come: a customer who tapped Done
    // has finished, and the transcript so far is the answer.
    _session?.completeFromTranscript();
  }

  /// Abandons the utterance entirely - the customer swiped the sheet away.
  Future<void> cancel() async {
    final session = _session;
    try {
      await _engine.cancel();
    } catch (e) {
      appLog('Speech cancel failed: $e');
    }
    session?.complete(const VoiceResult(VoiceOutcome.cancelled));
    _session = null;
  }

  Future<bool> _hasPermission() async {
    try {
      return await _engine.hasPermission;
    } catch (_) {
      return false;
    }
  }
}

/// One listening attempt.
class _Session {
  _Session({this.onPartial});

  final ValueChanged<String>? onPartial;

  final Completer<VoiceResult> _completer = Completer<VoiceResult>();
  final StringBuffer _transcript = StringBuffer();

  Timer? _backstop;

  /// Whether the recogniser ever confirmed it was listening. Distinguishes
  /// "the customer said nothing" from "it never started".
  bool _started = false;

  Future<VoiceResult> get future => _completer.future;

  void markStarted() => _started = true;

  void armBackstop(Duration after) {
    _backstop = Timer(after, () {
      if (_completer.isCompleted) return;
      // The platform neither finished nor failed. Whatever was heard is the
      // answer; an empty one is reported as silence rather than as a fault,
      // because the customer has no way to tell the difference and "try
      // again" is the useful advice either way.
      completeFromTranscript();
    });
  }

  void onResult(String words, bool isFinal) {
    if (isFinal) {
      _transcript
        ..clear()
        ..write(words);
      complete(VoiceResult(VoiceOutcome.heard, transcript: words.trim()));
      return;
    }

    // Partial. Kept so that a session ending without a final result - which
    // Android does more often than its documentation suggests - still returns
    // what the customer actually said.
    _transcript
      ..clear()
      ..write(words);
    onPartial?.call(words);
  }

  void completeFromTranscript() {
    final text = _transcript.toString().trim();
    if (text.isNotEmpty) {
      complete(VoiceResult(VoiceOutcome.heard, transcript: text));
      return;
    }
    complete(
        VoiceResult(_started ? VoiceOutcome.noSpeech : VoiceOutcome.failed));
  }

  void complete(VoiceResult result) {
    _backstop?.cancel();
    _backstop = null;
    if (_completer.isCompleted) return;
    _completer.complete(result);
  }
}
