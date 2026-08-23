import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/util/app_haptics.dart';
import 'package:gpstore/core/util/haptic_widgets.dart';

/// One physical tap must produce exactly ONE haptic.
///
/// Duplicate feedback is the most common way this goes wrong and the hardest
/// to notice in code review: a wrapper buzzes, then the thing it wraps buzzes
/// too, and the result feels broken rather than responsive. AppHaptics counts
/// its calls precisely so this can be asserted instead of hoped for.
void main() {
  setUp(() {
    AppHaptics.resetForTest();
    // No platform channel in a widget test; counting still works.
    AppHaptics.enabled = false;
  });

  testWidgets('a tap fires exactly one haptic and runs the callback', (tester) async {
    var taps = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: HapticInkWell(onTap: () => taps++, child: const Text('Buy')),
      ),
    ));

    await tester.tap(find.text('Buy'));
    await tester.pump();

    expect(taps, 1);
    expect(AppHaptics.callCount, 1);
  });

  testWidgets('NESTED wrappers still produce one haptic for one tap', (tester) async {
    // The shape the brief calls out: a card wrapping an image wrapping a
    // gesture. Only one recognizer wins the arena, so only the innermost
    // callback runs - but this pins it, because getting it wrong is
    // invisible until a device is in your hand.
    var outer = 0;
    var inner = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: HapticInkWell(
          onTap: () => outer++,
          child: HapticTap(onTap: () => inner++, child: const Text('Photo')),
        ),
      ),
    ));

    await tester.tap(find.text('Photo'));
    await tester.pump();

    expect(inner, 1);
    expect(outer, 0, reason: 'the inner gesture won the arena; the outer must not also fire');
    expect(AppHaptics.callCount, 1, reason: 'one physical tap, one haptic');
  });

  testWidgets('a disabled control stays silent', (tester) async {
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: HapticInkWell(onTap: null, child: Text('Unavailable'))),
    ));

    await tester.tap(find.text('Unavailable'));
    await tester.pump();

    // Feedback for a tap that does nothing tells the finger something untrue.
    expect(AppHaptics.callCount, 0);
  });

  testWidgets('a long press uses the heavier feel, once', (tester) async {
    var pressed = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: HapticInkWell(onLongPress: () => pressed++, child: const Text('Item')),
      ),
    ));

    await tester.longPress(find.text('Item'));
    await tester.pump();

    expect(pressed, 1);
    expect(AppHaptics.callCount, 1);
  });

  test('hapticize returns null for a null callback, so disabled stays disabled', () {
    expect(hapticize(null), isNull);
    expect(hapticizeValue<bool>(null), isNull);
  });

  test('hapticize fires once and passes the value through', () {
    bool? received;
    final wrapped = hapticizeValue<bool>((v) => received = v);

    wrapped!(true);

    expect(received, isTrue);
    expect(AppHaptics.callCount, 1);
  });

  testWidgets('scrolling never buzzes', (tester) async {
    // Part of the brief, and the reason a global pointer hook was rejected:
    // a fling across the catalogue must be silent.
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ListView(
          children: List.generate(
            40,
            (i) => HapticInkWell(onTap: () {}, child: SizedBox(height: 60, child: Text('Row $i'))),
          ),
        ),
      ),
    ));

    await tester.fling(find.text('Row 1'), const Offset(0, -400), 1200);
    await tester.pumpAndSettle();

    expect(AppHaptics.callCount, 0, reason: 'a scroll is not an intentional tap');
  });
}
