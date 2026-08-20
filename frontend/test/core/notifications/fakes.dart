import 'dart:async';

import 'package:gpstore/core/notifications/key_value_store.dart';
import 'package:gpstore/core/notifications/speech_engine.dart';

/// In-memory stand-in for secure storage.
class FakeKeyValueStore implements KeyValueStore {
  FakeKeyValueStore({this.failReads = false, this.failWrites = false});

  final Map<String, String> values = {};
  bool failReads;
  bool failWrites;

  /// Delays the read so two concurrent claims genuinely interleave, which is
  /// what a real keystore round trip does and what the log's lock must
  /// survive.
  Duration readDelay = Duration.zero;

  @override
  Future<String?> read(String key) async {
    if (readDelay > Duration.zero) await Future<void>.delayed(readDelay);
    if (failReads) throw StateError('storage unavailable');
    return values[key];
  }

  @override
  Future<void> write(String key, String value) async {
    if (failWrites) throw StateError('storage unavailable');
    values[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }
}

/// Records what was spoken, when, and whether two lines ever overlapped.
class FakeSpeechEngine implements SpeechEngine {
  final List<String> spoken = [];
  int configureCalls = 0;
  int stopCalls = 0;

  /// True if speak() was ever entered while a previous speak() had not yet
  /// returned - i.e. two announcements talking over each other.
  bool sawOverlap = false;

  bool failConfigure = false;
  bool failSpeak = false;

  /// How long each line takes to "speak". Non-zero is what makes an overlap
  /// detectable at all.
  Duration speakDuration = Duration.zero;

  bool _speaking = false;

  @override
  Future<void> configure() async {
    configureCalls++;
    if (failConfigure) throw StateError('tts unavailable');
  }

  @override
  Future<void> speak(String line) async {
    if (_speaking) sawOverlap = true;
    _speaking = true;
    try {
      if (speakDuration > Duration.zero) await Future<void>.delayed(speakDuration);
      if (failSpeak) throw StateError('tts engine died');
      spoken.add(line);
    } finally {
      _speaking = false;
    }
  }

  @override
  Future<void> stop() async {
    stopCalls++;
  }
}
