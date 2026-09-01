import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/admin/domain/presence_model.dart';
import '../../features/admin/presentation/admin_providers.dart';
import '../design/admin_components.dart';
import '../design/admin_tokens.dart';

/// The wall clock, and how many people are in the shop right now.
///
/// Two things a shopkeeper glances at rather than reads, so they are one
/// panel: "what time is it and is anyone here" is a single question.
///
/// THE CLOCK IS THE DEVICE'S OWN LOCAL TIME. It is not fetched, because a
/// clock that depended on the network would freeze the moment the shop's
/// connection dropped - exactly when someone is most likely to be staring at
/// the dashboard wondering what is going on. DateTime.now() already respects
/// the device's timezone and DST.
///
/// THE COUNT IS NOT. It cannot be: only the server knows who is talking to
/// it. So the two halves of this panel refresh on different clocks - the
/// dial every second from a local timer, the count every 30 seconds from the
/// backend - and the panel is honest about which is which.
class AdminLiveClockPanel extends ConsumerStatefulWidget {
  const AdminLiveClockPanel({super.key});

  @override
  ConsumerState<AdminLiveClockPanel> createState() => _AdminLiveClockPanelState();
}

class _AdminLiveClockPanelState extends ConsumerState<AdminLiveClockPanel> {
  /// EVERY timer this widget starts is held, including the one-shot alignment
  /// timer below. That one was originally fired and forgotten, on the
  /// reasoning that it lasts under a second and its callback checks `mounted`
  /// - but a timer nobody holds is a timer dispose() cannot cancel, and
  /// Flutter's test binding fails any test whose widget tree is torn down
  /// with one still pending. It failed four existing admin dashboard tests
  /// the moment this panel was added to the screen.
  Timer? _align;
  Timer? _tick;
  Timer? _poll;
  DateTime _now = DateTime.now();

  @override
  void initState() {
    super.initState();
    // Aligned to the next whole second rather than started immediately, so
    // the seconds hand steps when the second actually changes instead of
    // drifting a few hundred milliseconds off for the life of the screen.
    final msToNextSecond = 1000 - DateTime.now().millisecond;
    _align = Timer(Duration(milliseconds: msToNextSecond), () {
      if (!mounted) return;
      setState(() => _now = DateTime.now());
      _tick = Timer.periodic(const Duration(seconds: 1), (_) {
        if (!mounted) return;
        setState(() => _now = DateTime.now());
      });
    });

    // 30s, not 1s: the count is a five-minute rolling window, so polling it
    // every second would be 300 requests per shopper-minute to watch a number
    // that cannot meaningfully change that fast.
    _poll = Timer.periodic(const Duration(seconds: 30), (_) {
      if (!mounted) return;
      ref.invalidate(adminPresenceProvider);
    });
  }

  @override
  void dispose() {
    _align?.cancel();
    _tick?.cancel();
    _poll?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final presence = ref.watch(adminPresenceProvider);

    return AdminSectionCard(
      title: 'Right now',
      child: LayoutBuilder(
        builder: (context, constraints) {
          // The dial and the readouts sit side by side when there is room and
          // stack when there is not, so this works on a phone and on the wide
          // two-pane layout without a second widget.
          final wide = constraints.maxWidth >= 420;
          final dial = _AnalogDial(now: _now);
          final readout = _Readout(now: _now, presence: presence);

          if (!wide) {
            return Column(
              children: [
                SizedBox(height: 140, width: 140, child: dial),
                const SizedBox(height: AdminSpacing.lg),
                readout,
              ],
            );
          }
          return Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              SizedBox(height: 150, width: 150, child: dial),
              const SizedBox(width: AdminSpacing.xl),
              Expanded(child: readout),
            ],
          );
        },
      ),
    );
  }
}

class _Readout extends StatelessWidget {
  const _Readout({required this.now, required this.presence});

  final DateTime now;
  final AsyncValue<PresenceSnapshot> presence;

  static const _days = <String>[
    'Monday', 'Tuesday', 'Wednesday', 'Thursday',
    'Friday', 'Saturday', 'Sunday',
  ];
  static const _months = <String>[
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
  ];

  String get _time {
    // 12-hour with AM/PM: this is a shopkeeper's clock, not a log timestamp.
    final hour12 = now.hour % 12 == 0 ? 12 : now.hour % 12;
    final mm = now.minute.toString().padLeft(2, '0');
    final ss = now.second.toString().padLeft(2, '0');
    final suffix = now.hour < 12 ? 'AM' : 'PM';
    return '$hour12:$mm:$ss $suffix';
  }

  String get _date =>
      '${now.day} ${_months[now.month - 1]} ${now.year}';

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          _time,
          style: const TextStyle(
            fontSize: 30,
            fontWeight: FontWeight.w700,
            color: AdminColors.textPrimary,
            // Digits that do not shuffle sideways as the seconds tick. Without
            // this the whole line jitters once a second, which is the kind of
            // thing that makes a dashboard feel cheap.
            fontFeatures: [FontFeature.tabularFigures()],
          ),
        ),
        const SizedBox(height: 2),
        Text(
          '${_days[now.weekday - 1]}, $_date',
          style: const TextStyle(
            fontSize: 13,
            color: AdminColors.textSecondary,
            fontWeight: FontWeight.w500,
          ),
        ),
        const SizedBox(height: AdminSpacing.lg),
        const Divider(height: 1, color: AdminColors.border),
        const SizedBox(height: AdminSpacing.lg),
        _OnlineNow(presence: presence),
      ],
    );
  }
}

class _OnlineNow extends StatelessWidget {
  const _OnlineNow({required this.presence});

  final AsyncValue<PresenceSnapshot> presence;

  @override
  Widget build(BuildContext context) {
    return presence.when(
      loading: () => const _OnlineRow(
        value: '--',
        caption: 'Counting shoppers',
        tone: AdminColors.textMuted,
      ),
      // A failed fetch says so. It does not say zero - see PresenceSnapshot.
      error: (_, __) => const _OnlineRow(
        value: '--',
        caption: 'Count unavailable',
        tone: AdminColors.textMuted,
      ),
      data: (snapshot) {
        final count = snapshot.onlineNow;
        if (!snapshot.available || count == null) {
          return const _OnlineRow(
            value: '--',
            caption: 'Count unavailable',
            tone: AdminColors.textMuted,
          );
        }
        return _OnlineRow(
          value: '$count',
          // The definition sits under the number, because "12 online" means
          // nothing without the window it was measured over.
          caption: count == 1
              ? 'shopper active ${snapshot.windowLabel}'
              : 'shoppers active ${snapshot.windowLabel}',
          tone: count > 0 ? AdminColors.success : AdminColors.textSecondary,
        );
      },
    );
  }
}

class _OnlineRow extends StatelessWidget {
  const _OnlineRow({
    required this.value,
    required this.caption,
    required this.tone,
  });

  final String value;
  final String caption;
  final Color tone;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Container(
          height: 9,
          width: 9,
          decoration: BoxDecoration(color: tone, shape: BoxShape.circle),
        ),
        const SizedBox(width: AdminSpacing.sm),
        Text(
          value,
          style: TextStyle(
            fontSize: 26,
            fontWeight: FontWeight.w700,
            color: tone,
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
        const SizedBox(width: AdminSpacing.sm),
        Expanded(
          child: Text(
            caption,
            style: const TextStyle(
              fontSize: 12,
              color: AdminColors.textSecondary,
              height: 1.3,
            ),
          ),
        ),
      ],
    );
  }
}

/// A real dial, drawn rather than animated from an image.
///
/// One CustomPainter repainting a handful of lines once a second is cheaper
/// than any asset-based clock, and it inherits the panel's colours so it does
/// not look pasted in from somewhere else.
class _AnalogDial extends StatelessWidget {
  const _AnalogDial({required this.now});

  final DateTime now;

  @override
  Widget build(BuildContext context) {
    return RepaintBoundary(
      child: CustomPaint(
        painter: _DialPainter(now),
        // A time is a poor thing to convey by shape alone, so the dial carries
        // the same reading in words for a screen reader rather than being
        // announced as an unlabelled image.
        child: Semantics(
          label: 'Clock showing '
              '${now.hour.toString().padLeft(2, '0')}:'
              '${now.minute.toString().padLeft(2, '0')}',
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

class _DialPainter extends CustomPainter {
  _DialPainter(this.now);

  final DateTime now;

  @override
  void paint(Canvas canvas, Size size) {
    final centre = Offset(size.width / 2, size.height / 2);
    final radius = math.min(size.width, size.height) / 2 - 2;

    canvas.drawCircle(
      centre,
      radius,
      Paint()..color = AdminColors.neutralBg,
    );
    canvas.drawCircle(
      centre,
      radius,
      Paint()
        ..color = AdminColors.border
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );

    // Twelve ticks, the quarters longer - enough to read an angle against,
    // without drawing sixty marks that would just be noise at this size.
    for (var i = 0; i < 12; i++) {
      final angle = (math.pi / 6) * i - math.pi / 2;
      final quarter = i % 3 == 0;
      final inner = radius - (quarter ? 11 : 6);
      canvas.drawLine(
        centre + Offset(math.cos(angle), math.sin(angle)) * inner,
        centre + Offset(math.cos(angle), math.sin(angle)) * (radius - 3),
        Paint()
          ..color = quarter ? AdminColors.textSecondary : AdminColors.borderStrong
          ..strokeWidth = quarter ? 2.5 : 1.5
          ..strokeCap = StrokeCap.round,
      );
    }

    // The hour hand moves with the minutes rather than jumping on the hour,
    // which is what a real clock does and what makes the angle readable
    // between hours.
    final hourAngle =
        (math.pi / 6) * (now.hour % 12 + now.minute / 60) - math.pi / 2;
    final minuteAngle = (math.pi / 30) * now.minute - math.pi / 2;
    final secondAngle = (math.pi / 30) * now.second - math.pi / 2;

    _hand(canvas, centre, hourAngle, radius * 0.50, 4.5, AdminColors.textPrimary);
    _hand(canvas, centre, minuteAngle, radius * 0.72, 3.0, AdminColors.textPrimary);
    _hand(canvas, centre, secondAngle, radius * 0.80, 1.4, AdminColors.primary);

    canvas.drawCircle(centre, 3.5, Paint()..color = AdminColors.primary);
  }

  void _hand(Canvas canvas, Offset centre, double angle, double length,
      double width, Color color) {
    canvas.drawLine(
      centre,
      centre + Offset(math.cos(angle), math.sin(angle)) * length,
      Paint()
        ..color = color
        ..strokeWidth = width
        ..strokeCap = StrokeCap.round,
    );
  }

  // Repaint only when the displayed second actually changes. The panel calls
  // setState once a second, but a parent rebuild for any other reason must
  // not cost a repaint of the dial.
  @override
  bool shouldRepaint(_DialPainter oldDelegate) =>
      oldDelegate.now.second != now.second ||
      oldDelegate.now.minute != now.minute ||
      oldDelegate.now.hour != now.hour;
}
