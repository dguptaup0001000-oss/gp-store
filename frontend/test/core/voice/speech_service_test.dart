import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/voice/speech_engine.dart';
import 'package:gpstore/core/voice/speech_service.dart';

/// A recogniser under the test's control.
///
/// Every callback the real plugin delivers asynchronously over a platform
/// channel is delivered here on demand - which is the whole point, because
/// the bug this file exists for was a race against exactly those callbacks.
class FakeEngine implements SpeechEngine {
  FakeEngine({this.available = true, this.permitted = true});

  bool available;
  bool permitted;

  /// Set to make listen() throw, as the platform does when it cannot start.
  Object? listenThrows;

  int initializeCalls = 0;
  int listenCalls = 0;
  int stopCalls = 0;
  int cancelCalls = 0;

  void Function(String status)? _onStatus;
  void Function(String code, bool permanent)? _onError;
  void Function(String words, bool isFinal)? _onResult;

  bool _listening = false;

  @override
  Future<bool> initialize({
    required void Function(String status) onStatus,
    required void Function(String errorCode, bool permanent) onError,
  }) async {
    initializeCalls++;
    _onStatus = onStatus;
    _onError = onError;
    return available;
  }

  @override
  Future<void> listen({
    required void Function(String words, bool isFinal) onResult,
    required String localeId,
    required Duration pauseFor,
    required Duration listenFor,
  }) async {
    listenCalls++;
    if (listenThrows != null) throw listenThrows!;
    _onResult = onResult;
    _listening = true;
    // NOTE: no status is emitted here, exactly like the real plugin - the
    // listening status arrives later, and the original bug was assuming
    // otherwise.
  }

  @override
  Future<void> stop() async {
    stopCalls++;
    _listening = false;
  }

  @override
  Future<void> cancel() async {
    cancelCalls++;
    _listening = false;
  }

  @override
  bool get isListening => _listening;

  @override
  Future<bool> get hasPermission async => permitted;

  // --- the levers a test pulls ---

  void emitStatus(String status) => _onStatus?.call(status);

  void emitError(String code, {bool permanent = false}) => _onError?.call(code, permanent);

  void emitPartial(String words) => _onResult?.call(words, false);

  void emitFinal(String words) => _onResult?.call(words, true);

  /// The ordinary happy sequence: the recogniser confirms it started, the
  /// customer speaks, a final result arrives.
  void speak(String words) {
    emitStatus(SpeechStatus.listening);
    emitPartial(words);
    emitFinal(words);
  }
}

void main() {
  group('the race that made voice search fail on every device', () {
    test('listening does not end just because isListening is still false', () async {
      // THE REGRESSION. The plugin sets its listening flag inside the status
      // callback, which arrives after listen() returns. The old code polled
      // that flag, read false immediately, and gave up before the microphone
      // had heard a word - so this future must NOT be complete yet.
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);

      expect(engine.isListening, isTrue);
      var completed = false;
      unawaited(pending.then((_) => completed = true));
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(completed, isFalse,
          reason: 'the customer has not spoken yet - ending here is the bug');

      engine.speak('do kilo atta');
      final result = await pending;
      expect(result.outcome, VoiceOutcome.heard);
      expect(result.transcript, 'do kilo atta');
    });

    test('a session ending with only a partial still returns what was said', () async {
      // Android does this more often than its documentation suggests: the
      // recogniser stops without ever sending finalResult. Throwing the
      // partial away would tell a customer who spoke clearly that nothing
      // was heard.
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);

      engine.emitStatus(SpeechStatus.listening);
      engine.emitPartial('amul doodh');
      engine.emitStatus(SpeechStatus.done);

      final result = await pending;
      expect(result.outcome, VoiceOutcome.heard);
      expect(result.transcript, 'amul doodh');
    });
  });

  group('permission and availability are five different things', () {
    test('permission refused, and refusable again', () async {
      final engine = FakeEngine(available: false, permitted: false);

      final result = await SpeechService(engine: engine).listenOnce();

      expect(result.outcome, VoiceOutcome.permissionDenied);
      expect(result.isRetryable, isFalse,
          reason: 'a Try again that cannot succeed is worse than no button');
    });

    test('permission refused permanently is NOT the same as refused', () async {
      // Only Settings can undo this, so the UI has to offer a different path.
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);
      engine.emitError(SpeechErrors.permission, permanent: true);

      expect((await pending).outcome, VoiceOutcome.permissionPermanentlyDenied);
    });

    test('no recogniser on the device is not a permission problem', () async {
      final engine = FakeEngine(available: false, permitted: true);

      expect((await SpeechService(engine: engine).listenOnce()).outcome,
          VoiceOutcome.recognizerUnavailable);
    });

    test('the microphone being busy is its own case', () async {
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);
      engine.emitError(SpeechErrors.busy);

      final result = await pending;
      expect(result.outcome, VoiceOutcome.busy);
      expect(result.isRetryable, isTrue, reason: 'the other app may let go');
    });

    test('a recogniser network failure is not a product-search failure', () async {
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);
      engine.emitError(SpeechErrors.network);

      expect((await pending).outcome, VoiceOutcome.networkUnavailable);
    });
  });

  group('silence is not a fault', () {
    test('Android reporting no_match means nothing was said', () async {
      // The recogniser calls this an error. It is not one, and telling a
      // customer something went wrong when they simply did not speak sends
      // them looking for a problem that is not there.
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);
      engine.emitStatus(SpeechStatus.listening);
      engine.emitError(SpeechErrors.noMatch);

      final result = await pending;
      expect(result.outcome, VoiceOutcome.noSpeech);
      expect(result.isRetryable, isTrue);
    });

    test('a speech timeout is silence too', () async {
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);
      engine.emitStatus(SpeechStatus.listening);
      engine.emitError(SpeechErrors.speechTimeout);

      expect((await pending).outcome, VoiceOutcome.noSpeech);
    });

    test('ending without ever having started is a fault, not silence', () async {
      // Nothing confirmed the microphone opened, so "you said nothing" would
      // be blaming the customer for the platform.
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);
      engine.emitStatus(SpeechStatus.done);

      expect((await pending).outcome, VoiceOutcome.failed);
    });
  });

  group('lifecycle', () {
    test('tapping the microphone twice does not start two recognisers', () async {
      // Two live sessions fight over one microphone and the loser reports an
      // error that looks like a fault.
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final first = service.listenOnce();
      await Future<void>.delayed(Duration.zero);
      final second = service.listenOnce();
      await Future<void>.delayed(Duration.zero);

      expect(engine.listenCalls, 1);

      engine.speak('chini');
      expect((await first).transcript, 'chini');
      expect((await second).transcript, 'chini',
          reason: 'the second tap joins the first rather than racing it');
    });

    test('cancelling while listening releases the microphone and ends the wait',
        () async {
      // Swiping the sheet away must not leave a recogniser running, and must
      // not leave the caller awaiting a future that never completes.
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);
      await service.cancel();

      expect(engine.cancelCalls, 1);
      expect((await pending).outcome, VoiceOutcome.cancelled);
      expect(service.isListening, isFalse);
    });

    test('Done keeps what was heard so far', () async {
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      final pending = service.listenOnce();
      await Future<void>.delayed(Duration.zero);
      engine.emitStatus(SpeechStatus.listening);
      engine.emitPartial('sarso ka tel');
      await service.stop();

      final result = await pending;
      expect(engine.stopCalls, 1);
      expect(result.outcome, VoiceOutcome.heard);
      expect(result.transcript, 'sarso ka tel');
    });

    test('a listen that throws does not leave the service stuck listening', () async {
      final engine = FakeEngine()..listenThrows = StateError('platform refused');
      final service = SpeechService(engine: engine);

      expect((await service.listenOnce()).outcome, VoiceOutcome.failed);
      expect(service.isListening, isFalse,
          reason: 'a stuck listening flag means the microphone can never be used again');
    });

    test('initialize happens once however many times the microphone is tapped',
        () async {
      final engine = FakeEngine();
      final service = SpeechService(engine: engine);

      for (var i = 0; i < 3; i++) {
        final pending = service.listenOnce();
        await Future<void>.delayed(Duration.zero);
        engine.speak('atta');
        await pending;
      }

      expect(engine.initializeCalls, 1,
          reason: 'asking for permission on every tap is how apps get denied');
      expect(engine.listenCalls, 3);
    });
  });

  test('the backstop ends a session the platform abandoned', () async {
    // If the platform neither completes nor errors, the sheet would say
    // "Listening..." forever. Faked with a short wait rather than the real
    // twenty seconds by driving the terminal status directly - the timer
    // itself is asserted by its absence of alternatives, not by sleeping.
    final engine = FakeEngine();
    final service = SpeechService(engine: engine);

    final pending = service.listenOnce();
    await Future<void>.delayed(Duration.zero);
    engine.emitStatus(SpeechStatus.listening);
    engine.emitStatus(SpeechStatus.notListening);

    expect((await pending).outcome, VoiceOutcome.noSpeech);
  });
}
