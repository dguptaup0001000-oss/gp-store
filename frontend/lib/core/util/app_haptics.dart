import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// One place that decides how the app feels under a finger.
///
/// WHY THIS EXISTS. Haptics were already called in about twenty places, each
/// picking an intensity by hand - lightImpact here, selectionClick there,
/// mediumImpact somewhere else - with no rule about which meant what. The
/// result was a shop that buzzed inconsistently and, on the screens nobody
/// had got round to (Store Management, Logout, Delete Account), not at all.
///
/// THE RULE, so a future call site does not have to invent one:
///
///   [selection] - moving through the interface. Tabs, chips, rail taps,
///                 navigation. The lightest thing available, because it
///                 happens constantly and anything heavier becomes noise.
///
///   [action]    - the customer changed something. Add to cart, remove from
///                 cart, save to wishlist. Noticeable, because the feedback
///                 IS the confirmation - it is what tells a thumb the tap
///                 landed without waiting for the network.
///
///   [heavy]     - consequential and hard to undo. Logging out everywhere,
///                 deleting an account, confirming an order. Deliberately
///                 distinct so the hand notices a moment before the mind
///                 does.
///
/// NOT A WRAPPER FOR ITS OWN SAKE. Centralising also means the "should this
/// buzz at all" question is answered once - see [enabled] - rather than
/// twenty times.
class AppHaptics {
  const AppHaptics._();

  /// Turned off in tests so a widget test does not fail on a missing platform
  /// channel, and so nothing is asserted about vibration hardware that a test
  /// runner does not have.
  ///
  /// Kept as a mutable static rather than injected: a haptic is a
  /// fire-and-forget side effect at the leaf of the widget tree, and threading
  /// a provider through every button to reach it would cost far more than it
  /// buys.
  @visibleForTesting
  static bool enabled = true;

  /// Counts calls in tests, so "one tap produces exactly one haptic" is an
  /// assertion rather than a hope. Duplicate feedback on a single tap is the
  /// most common way this goes wrong: a wrapper buzzes, then the thing it
  /// wraps buzzes too, and the result feels broken rather than responsive.
  @visibleForTesting
  static int callCount = 0;

  @visibleForTesting
  static void resetForTest() {
    callCount = 0;
    enabled = true;
  }

  static void _fire(void Function() impact) {
    callCount++;
    if (!enabled) return;
    // Deliberately not awaited. Haptics are advisory: on a device with the
    // system setting off, or no vibration motor, the platform simply does
    // nothing. Awaiting it would put a platform round-trip in front of a
    // navigation that should feel instant.
    impact();
  }

  /// Navigation and selection. The lightest feedback available.
  static void selection() => _fire(HapticFeedback.selectionClick);

  /// The customer changed something that persists.
  static void action() => _fire(HapticFeedback.lightImpact);

  /// Consequential, hard to undo.
  static void heavy() => _fire(HapticFeedback.mediumImpact);
}
