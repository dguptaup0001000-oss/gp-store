import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/theme/app_theme.dart';

/// WCAG relative luminance, then the standard contrast ratio.
///
/// Written out rather than eyeballed because "is this dark green readable
/// under white text" is exactly the judgement that goes wrong when somebody
/// later lightens the brand colour to make it friendlier.
double _luminance(Color c) {
  double channel(double v) {
    final s = v / 255.0;
    return s <= 0.03928 ? s / 12.92 : math.pow((s + 0.055) / 1.055, 2.4).toDouble();
  }

  return 0.2126 * channel(c.r * 255) + 0.7152 * channel(c.g * 255) + 0.0722 * channel(c.b * 255);
}

double _contrast(Color a, Color b) {
  final la = _luminance(a);
  final lb = _luminance(b);
  final lighter = math.max(la, lb);
  final darker = math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

void main() {
  group('GP-Store palette', () {
    test('primary is a deep green, not a blue', () {
      // The identity rule, pinned: green must dominate, and the colour must
      // be dark enough to read as forest rather than leaf.
      expect(AppColors.primary.g, greaterThan(AppColors.primary.b));
      expect(AppColors.primary.g, greaterThan(AppColors.primary.r));
      expect(_luminance(AppColors.primary), lessThan(0.2));
    });

    test('white text on the primary green clears WCAG AA', () {
      // Every primary button in the app is white-on-primary.
      expect(_contrast(AppColors.primary, const Color(0xFFFFFFFF)), greaterThanOrEqualTo(4.5));
    });

    test('the ADD button colour is the primary green', () {
      // "The ADD button should use the GP-Store primary green" - the most
      // repeated button in the app is the one that must carry the identity.
      expect(AppColors.cart, AppColors.primary);
    });

    test('discount badges stay coral, distinct from the brand green', () {
      // A shopper learns the money-off colour once. If accent ever drifts
      // toward the brand colour, that signal is lost.
      expect(AppColors.accent.r, greaterThan(AppColors.accent.g));
      expect(_contrast(AppColors.accent, AppColors.primary), greaterThan(2.0));
    });

    test('the page ground is a pale tint, not pure white', () {
      // White cards only lift off the page if the page is not also white.
      expect(AppColors.background, isNot(AppColors.cardBackground));
      expect(_luminance(AppColors.background), greaterThan(0.8));
      expect(_contrast(AppColors.cardBackground, AppColors.background), lessThan(1.15));
    });

    test('premium surfaces are warm, and the ground is not', () {
      // Ivory reads as paper because it is warm (red above blue); the mint
      // ground is cool. If both drifted the same way the distinction that
      // marks a premium section would vanish.
      expect(AppColors.ivory.r, greaterThan(AppColors.ivory.b));
      expect(AppColors.background.b, greaterThanOrEqualTo(AppColors.background.r));
    });

    test('body text clears WCAG AA on both the page and on cards', () {
      expect(_contrast(AppColors.textPrimary, AppColors.background), greaterThanOrEqualTo(4.5));
      expect(_contrast(AppColors.textPrimary, AppColors.cardBackground), greaterThanOrEqualTo(4.5));
      expect(_contrast(AppColors.textSecondary, AppColors.cardBackground), greaterThanOrEqualTo(4.5));
    });
  });
}
