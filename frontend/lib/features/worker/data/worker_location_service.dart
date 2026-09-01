import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';

import 'worker_repository.dart';
import '../../../core/logging/app_log.dart';
import '../../../core/notifications/notification_permission.dart';

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
/// IT RUNS AS A FOREGROUND SERVICE, and it has to. This previously used a
/// plain position stream, which Android stops the moment the app is no longer
/// visible - so tracking died on screen-lock. A rider cannot hold a screen
/// open while riding a motorbike, which meant the position was shared during
/// exactly the minutes nobody needed it and stopped for all the rest. The
/// notification the service is required to post is a feature rather than a
/// cost: the rider can see, at a glance and at any time, that their position
/// is being shared, and it disappears the moment it is not.
///
/// STILL NOT BACKGROUND LOCATION. The service is started from a visible
/// screen and needs only the while-in-use grant, so it can follow a rider
/// while they are working and cannot follow them when they are not. There is
/// no ACCESS_BACKGROUND_LOCATION in the manifest, and it is explicitly
/// removed there so no merged plugin manifest can add one.
///
/// WHAT IT DELIBERATELY DOES NOT DO:
///
///   - No queueing of old positions. A location is only worth anything while
///     it is current; replaying an hour-old one would put a pin somewhere the
///     rider no longer is, timestamped as though it were now.
///   - No error banners. A dropped position is replaced by the next one.
///     Interrupting somebody riding a bike is worse than a gap on a map.
class WorkerLocationService {
  static const int _defaultDistanceFilterMetres = 75;

  WorkerLocationService({
    required this.repository,
    this.distanceFilterMetres = _defaultDistanceFilterMetres,
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

    // Ask for the notification permission BEFORE starting, and only here.
    // The service's ongoing notification is how a rider knows they are being
    // tracked, and on API 33+ it is hidden without this grant. Asking at this
    // moment - the first delivery, not first launch - is the only point where
    // the request explains itself. A refusal is not fatal: the service still
    // runs, so this never blocks the start.
    await _notificationPermission();

    try {
      _subscription = Geolocator.getPositionStream(
        locationSettings: _settings(),
      ).listen(_onPosition, onError: (Object error) {
        appLog('Location stream error: $error');
      });
    } catch (error) {
      // Android refuses to start a foreground service from the background,
      // and throws for a location-typed one without FOREGROUND_SERVICE_LOCATION
      // on API 34+. Neither may take the app down: a rider who cannot share
      // their position must still be able to scan and complete the delivery.
      appLog('Could not start location tracking: $error');
      _subscription = null;
      return 'Location sharing could not start on this phone. Everything else '
          'still works - your deliveries are unaffected.';
    }

    return null;
  }

  /// Android needs the foreground-service config; iOS ignores it.
  LocationSettings _settings() {
    // "High" rather than "best": best asks for the tightest possible fix and
    // keeps the radio busy chasing metres nobody needs to know.
    const accuracy = LocationAccuracy.high;

    if (defaultTargetPlatform == TargetPlatform.android) {
      return AndroidSettings(
        accuracy: accuracy,
        distanceFilter: distanceFilterMetres,
        foregroundNotificationConfig: const ForegroundNotificationConfig(
          notificationTitle: 'Sharing your location',
          // Says why it is running and when it stops, because a permanent
          // notification with no explanation is the kind users disable.
          notificationText:
              'Visible to the shop while you have a delivery out. Stops when '
              'you finish.',
          notificationChannelName: 'Delivery location',
          notificationIcon:
              AndroidResource(name: 'ic_launcher', defType: 'mipmap'),
          // The point of the service is to survive the screen going off.
          enableWakeLock: true,
          // Not swipe-dismissable: a rider who clears it by accident would
          // silently stop appearing on the shop's map with no way to tell.
          setOngoing: true,
        ),
      );
    }

    return const LocationSettings(
      accuracy: accuracy,
      distanceFilter: _defaultDistanceFilterMetres,
    );
  }

  /// Asks for POST_NOTIFICATIONS, swallowing anything that goes wrong.
  ///
  /// Deliberately best-effort. This permission only decides whether the
  /// service's notification is VISIBLE - not whether tracking works - so a
  /// plugin failure here must never stop a rider sharing their position.
  Future<void> _notificationPermission() async {
    try {
      await requestNotificationPermission();
    } catch (error) {
      appLog('Notification permission request failed: $error');
    }
  }

  /// Stops tracking and tears the foreground service down with it.
  ///
  /// Idempotent, and called from every exit: delivery completed, sign-out,
  /// account disabled, screen disposed. The notification disappearing is the
  /// rider's confirmation that they are no longer being followed, so this must
  /// not be skipped on any of those paths.
  Future<void> stop() async {
    final subscription = _subscription;
    _subscription = null;
    _lastSentAt = null;
    await subscription?.cancel();
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
