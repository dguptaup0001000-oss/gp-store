import 'package:flutter_tts/flutter_tts.dart';

/// The speech operations the announcer needs, behind an interface.
///
/// The queue's whole contract - one announcement at a time, in arrival order,
/// each waiting for the previous to finish - is only testable if [speak] can
/// be made to take a controllable amount of time. Against a real engine there
/// is no audio in a unit test and nothing to observe.
abstract class SpeechEngine {
  /// Must resolve when the line has finished being SPOKEN, not when playback
  /// starts. The queue depends on it.
  Future<void> speak(String line);

  /// Applies the announcement voice settings. Called once, lazily.
  Future<void> configure();

  Future<void> stop();
}

/// Production implementation, wrapping flutter_tts.
class FlutterTtsSpeechEngine implements SpeechEngine {
  FlutterTtsSpeechEngine({FlutterTts? tts}) : _tts = tts ?? FlutterTts();

  final FlutterTts _tts;

  @override
  Future<void> speak(String line) async {
    await _tts.speak(line);
  }

  @override
  Future<void> configure() async {
    // Makes speak() resolve when playback ENDS rather than when it starts.
    // Without this the queue would fire every announcement at once and they
    // would talk over each other.
    await _tts.awaitSpeakCompletion(true);

    // en-IN so Indian names and numbers are pronounced the way a shopkeeper
    // here expects, falling back to the device's own voice if that one is not
    // installed - setLanguage is a no-op rather than an error on an
    // unsupported locale.
    await _tts.setLanguage('en-IN');

    // Slightly under default: announcement speed, not audiobook speed. A
    // total misheard is worse than one heard a beat later.
    await _tts.setSpeechRate(0.48);
    await _tts.setPitch(1.0);

    // Volume is DELIBERATELY NOT SET. The device's media volume and
    // silent/DND state are the shop's business, and a soundbox that overrides
    // them to shout is the behaviour people disable. Leaving it at the engine
    // default means the announcement obeys the phone.
  }

  @override
  Future<void> stop() async {
    await _tts.stop();
  }
}
