import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';

import 'worker_repository.dart';

/// Reports where the worker is, while they are actually working.
///
/// THE WHOLE DESIGN IS ABOUT WHEN NOT TO RUN. A delivery worker's phone is a
/// cheap Android handset that has to last a shift, and continuous GPS is the
/// single most expensive thing an app can ask of it. So this starts only when
/// there is a live delivery to track, stops the moment there is not, and in
/// between reports on movement rather than on a clock.
///
/// [distanceFilterMetres] is what makes it cheap. The platform wakes this only
/// when the phone has actually moved that far, so a worker waiting at a level
/// crossing costs nothing, and a worker riding reports steadily. The interval
/// below is a floor, not a schedule: it throttles a fast rider rather than
/// polling a stationary one.
///
/// WHAT IT DELIBERATELY DOES NOT DO:
///
///   - No background or foreground service. When the app is not open, this
///     stops. Tracking a worker's phone while they are off shift is not
///     something a delivery app should be able to do at all, and the way to
///     guarantee that is to not have the capability.
///   - No queueing of old positions. A location is only worth anything while
///     it is current; replaying an hour-old one would put a pin somewhere the
///     rider no longer is, timestamped as though it were now.
///   - No error banners. A dropped position is replaced by the next one.
///     Interrupting somebody riding a bike is worse than a gap on a map.
class WorkerLocationService {
  WorkerLocationService({
    required this.repository,
    this.distanceFilterMetres = 75,
    this.minimumInterval = const Duration(seconds: 45),
  });

  final WorkerRepository repository;

  /// How far the phone must move before the platform wakes us.
  ///
  /// 75 m is roughly a village street. Smaller means a rider stopped at a
  /// junction produces a stream of near-identical fixes; much larger and the
  /// admin map stops being useful in the last few hundred metres, which is
  /// exactly when somebody is watching it.
  final int distanceFilterMetres;

  /// A floor on how often a position is sent, whatever the phone reports.
  final Duration minimumInterval;

  StreamSubscription<Position>? _subscription;
  DateTime? _lastSentAt;
  bool _inFlight = false;

  bool get isRunning => _subscription != null;

  /// Asks for permission and starts reporting. Safe to call when already running.
  ///
  /// Returns why it could not start, or null when it did. The caller shows
  /// that sentence once, on the screen, rather than as a repeating banner -
  /// permission problems are fixed in Settings, not by being told twice.
  Future<String?> start() async {
    if (_subscription != null) {
      return null;
    }

    if (!await Geolocator.isLocationServiceEnabled()) {
      return 'Location is turned off on this phone. Turn it on to share your position.';
    }

    LocationPermission permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.deniedForever) {
      return 'Location permission is permanently denied. Enable it in Settings > Apps.';
    }
    if (permission == LocationPermission.denied) {
      return 'Location permission was not granted. Your position will not be shared.';
    }

    _subscription = Geolocator.getPositionStream(
      locationSettings: LocationSettings(
        // "High" rather than "best": best asks for the tightest possible fix
        // and keeps the radio busy chasing metres nobody needs to know.
        accuracy: LocationAccuracy.high,
        distanceFilter: distanceFilterMetres,
      ),
    ).listen(_onPosition, onError: (Object error) {
      debugPrint('Location stream error: $error');
    });

    return null;
  }

  Future<void> stop() async {
    await _subscription?.cancel();
    _subscription = null;
    _lastSentAt = null;
  }

  Future<void> _onPosition(Position position) async {
    // Two guards, and both are about not queueing work on a bad connection.
    // _inFlight stops a slow request being followed by three more while it is
    // still going; the interval stops a fast rider sending more than the
    // admin screen could possibly use.
    if (_inFlight) {
      return;
    }
    final now = DateTime.now();
    if (_lastSentAt != null && now.difference(_lastSentAt!) < minimumInterval) {
      return;
    }

    _inFlight = true;
    try {
      final stored = await repository.reportLocation(
        latitude: position.latitude,
        longitude: position.longitude,
        // Sent so the server can throw away a fix too vague to be worth
        // drawing. A kilometre-wide cell-tower estimate rendered as a pin
        // looks exactly as confident as a real one.
        accuracyMeters: position.accuracy,
      );
      // Only a stored position counts towards the interval. A refused or
      // failed one should not buy the next attempt a 45-second wait.
      if (stored) {
        _lastSentAt = now;
      }
    } finally {
      _inFlight = false;
    }
  }
}
