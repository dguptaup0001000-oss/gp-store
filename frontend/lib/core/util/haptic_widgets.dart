import 'package:flutter/material.dart';

import 'app_haptics.dart';

/// Drop-in replacements that make a tap feel like a tap, without every call
/// site having to remember.
///
/// WHY WRAPPERS AND NOT A GLOBAL HOOK. Flutter has no supported way to be
/// told "an interactive widget was tapped". The pointer-level hooks that do
/// exist (GestureBinding's global pointer route) fire for scrolls, flings and
/// taps on inert decoration alike, so using one would buzz while a customer
/// flicks through the catalogue - the exact thing that must never happen. The
/// signal that actually means "intentional tap" is a tap recognizer winning
/// the gesture arena, and that is not exposed. So the honest mechanism is a
/// wrapper per gesture, kept to one line at the call site.
///
/// ONE TAP, ONE HAPTIC, EVEN NESTED. Nesting these is safe by construction:
/// for a single tap only one recognizer wins the arena, so an inner
/// [HapticInkWell] inside an outer one fires the inner callback and the outer
/// one never runs. The duplicate this file must prevent is the other shape -
/// a wrapper that buzzes AND a handler that also calls AppHaptics itself. The
/// rule is therefore: if you use these widgets, do not call AppHaptics inside
/// the callback you hand them. haptic_widgets_test.dart asserts the count.
///
/// A disabled control (null callback) must stay silent - feedback for a tap
/// that does nothing tells the finger something untrue.
class HapticInkWell extends StatelessWidget {
  const HapticInkWell({
    super.key,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.borderRadius,
    this.feedback = AppHapticFeedback.tap,
  });

  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final BorderRadius? borderRadius;
  final AppHapticFeedback feedback;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: borderRadius,
      onTap: onTap == null ? null : () => _fireThen(feedback, onTap!),
      // A long press is a deliberate, heavier gesture and reads better with
      // heavier feedback, whatever the tap was configured as.
      onLongPress: onLongPress == null
          ? null
          : () => _fireThen(AppHapticFeedback.heavy, onLongPress!),
      child: child,
    );
  }
}

/// For the taps that are not on a Material surface - an image, a bare
/// container, a custom card that paints its own background.
class HapticTap extends StatelessWidget {
  const HapticTap({
    super.key,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.behavior = HitTestBehavior.opaque,
    this.feedback = AppHapticFeedback.tap,
  });

  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final HitTestBehavior behavior;
  final AppHapticFeedback feedback;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: behavior,
      onTap: onTap == null ? null : () => _fireThen(feedback, onTap!),
      onLongPress: onLongPress == null
          ? null
          : () => _fireThen(AppHapticFeedback.heavy, onLongPress!),
      child: child,
    );
  }
}

/// Which of [AppHaptics]' three intensities a wrapper should use.
///
/// Named rather than passing a function so a call site reads as intent
/// ("this is an action") instead of plumbing.
enum AppHapticFeedback {
  /// Navigation, selection, opening things. The default.
  tap,

  /// The customer changed something that persists - add to cart, save.
  action,

  /// Consequential and hard to undo - delete, log out everywhere.
  heavy,
}

void _fireThen(AppHapticFeedback feedback, VoidCallback callback) {
  switch (feedback) {
    case AppHapticFeedback.tap:
      AppHaptics.tap();
    case AppHapticFeedback.action:
      AppHaptics.action();
    case AppHapticFeedback.heavy:
      AppHaptics.heavy();
  }
  callback();
}

/// Wraps an existing callback so it buzzes before it runs.
///
/// For the places a wrapper widget does not fit - a dialog action that
/// already has its own button widget, a custom clickable that takes a
/// callback, a method reference.
///
/// NON-NULLABLE IN AND OUT, and that is not a stylistic choice. Returning
/// VoidCallback? made every call site that feeds a REQUIRED callback fail to
/// compile:
///
///     The argument type 'VoidCallback?' can't be assigned to
///     the parameter type 'VoidCallback'
///
/// forty times over. A non-null return is assignable to both a nullable and a
/// non-nullable parameter, so this one shape fits every call site; use
/// [hapticizeOrNull] where the callback itself may be null.
VoidCallback hapticize(VoidCallback callback,
    {AppHapticFeedback feedback = AppHapticFeedback.tap}) {
  return () => _fireThen(feedback, callback);
}

/// The same, for a callback that may be null.
///
/// Returns null unchanged, so a disabled control stays disabled AND silent -
/// feedback for a tap that does nothing tells the finger something untrue.
VoidCallback? hapticizeOrNull(VoidCallback? callback,
    {AppHapticFeedback feedback = AppHapticFeedback.tap}) {
  if (callback == null) return null;
  return hapticize(callback, feedback: feedback);
}

/// The same, for the single-argument callbacks Switch, Checkbox, Radio and
/// DropdownButton use.
///
/// A value CHANGE is the customer acting, so this defaults to `action`
/// rather than `tap`: flipping a switch is a change that persists, and the
/// feedback is what confirms it landed.
ValueChanged<T> hapticizeValue<T>(ValueChanged<T> callback,
    {AppHapticFeedback feedback = AppHapticFeedback.action}) {
  return (T value) => _fireThen(feedback, () => callback(value));
}

/// The same, for a value callback that may be null - a control disabled by
/// passing null onChanged must stay disabled and silent.
ValueChanged<T>? hapticizeValueOrNull<T>(ValueChanged<T>? callback,
    {AppHapticFeedback feedback = AppHapticFeedback.action}) {
  if (callback == null) return null;
  return hapticizeValue(callback, feedback: feedback);
}
