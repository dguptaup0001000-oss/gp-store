import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Times how long the app is actually in the foreground and reports it once,
/// when it goes away.
///
/// WHAT THIS COLLECTS, IN FULL: a count of seconds. Not which screen, not
/// what was searched for, not what was looked at, not where the phone was.
/// A single number per foreground stretch. It is declared to the Play Store
/// as "App interactions" for exactly that reason - see
/// docs/PLAY_STORE_DECLARATIONS.md - and the narrowness is the point: the
/// shop wanted to tell a regular from somebody who installed the app once,
/// and that question does not need a behaviour log to answer.
///
/// WHY THE CLOCK STARTS ON RESUME, NOT ON LAUNCH. Timing from app start
/// would count the eight hours a phone sat in a pocket with the app
/// backgrounded. Foreground stretches are what "time spent" means to the
/// person reading it.
///
/// WHY IT REPORTS ON THE WAY OUT, NOT ON A TIMER. One request per
/// backgrounding, instead of a heartbeat that would put a request on the
/// network every minute the app is open - on a shop counter's connection
/// that is a real cost for a number nobody reads in real time. The trade is
/// that a session ended by a crash or a force-stop is never reported, which
/// is correct: undercounting is the safe direction for a figure like this.
///
/// [inactive] is deliberately not treated as leaving. A notification shade
/// pulled down or an incoming call is not the customer putting the app away,
/// and ending a session on every one of those would turn one visit into a
/// dozen and make the session count meaningless.
class AppSessionTracker extends ConsumerStatefulWidget {
  const AppSessionTracker({
    super.key,
    required this.child,
    this.onSessionEnded,
    this.minimumSession = const Duration(seconds: 3),
    this.clock,
  });

  final Widget child;

  /// Customer app: post the seconds. Admin and worker apps pass null, so
  /// staff phones are not timed at all.
  final void Function(WidgetRef ref, int seconds)? onSessionEnded;

  /// Below this, a foreground stretch is a bounce - a mis-tap, or the app
  /// briefly on screen during a task switch - and reporting it would inflate
  /// the session count with visits that never happened.
  final Duration minimumSession;

  /// The wall clock. Null means the real one; a test passes its own.
  ///
  /// It has to be the real clock and not `tester.pump`'s fake one: the thing
  /// being measured is how long a person held the app open, which is wall
  /// time, not frame time. That makes the durations here untestable unless
  /// the clock can come in from outside, so it can.
  final DateTime Function()? clock;

  @override
  ConsumerState<AppSessionTracker> createState() => _AppSessionTrackerState();
}

class _AppSessionTrackerState extends ConsumerState<AppSessionTracker>
    with WidgetsBindingObserver {
  /// Null means "not currently in a foreground stretch we are timing".
  DateTime? _enteredAt;

  DateTime _now() => (widget.clock ?? DateTime.now)();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // The app is already foregrounded when this widget first builds - there
    // will be no resume event for the launch itself.
    if (widget.onSessionEnded != null) _enteredAt = _now();
  }

  @override
  void dispose() {
    // NO REPORT ON THE WAY OUT. It is tempting to flush the last stretch
    // here, but this widget sits at the app root, so dispose means teardown -
    // and reading a Riverpod ref while the element is being unmounted is how
    // a clean exit turns into an exception on the way out the door. Losing
    // one unreported session is the cheaper mistake, and `paused` has
    // already fired in every ordinary backgrounding.
    _enteredAt = null;
    // Removed as carefully as it was added: a lingering observer on a
    // disposed State is a leak that only shows up as a crash much later.
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.resumed:
        _enteredAt ??= _now();
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
      case AppLifecycleState.detached:
        _endSession();
      case AppLifecycleState.inactive:
        break;
    }
  }

  void _endSession() {
    final startedAt = _enteredAt;
    _enteredAt = null;
    final report = widget.onSessionEnded;
    if (startedAt == null || report == null) return;

    final elapsed = _now().difference(startedAt);
    if (elapsed < widget.minimumSession) return;
    // A negative or absurd figure means the wall clock moved under us - a
    // timezone change, a manual clock adjustment, an NTP correction. Dropping
    // it is better than filing a number that is not a duration. The server
    // caps it again regardless; this is just not sending obvious rubbish.
    final seconds = elapsed.inSeconds;
    if (seconds <= 0 || seconds > const Duration(hours: 12).inSeconds) return;

    report(ref, seconds);
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
