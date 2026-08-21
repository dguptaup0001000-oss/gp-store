import 'dart:math' as math;
import 'package:flutter/material.dart';

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

/// Shortest angular distance between two hues, in degrees.
double _hueGap(Color a, Color b) {
  final difference = (HSLColor.fromColor(a).hue - HSLColor.fromColor(b).hue).abs();
  // Hue is circular: 350 and 10 are 20 apart, not 340.
  return difference > 180 ? 360 - difference : difference;
}

void main() {
  group('GP-Store palette', () {
    test('primary is a deep violet, not a green and not a bright purple', () {
      // The identity rule, pinned. Blue must lead, red must be present (that
      // is what makes it violet rather than blue), and green must trail.
      expect(AppColors.primary.b, greaterThan(AppColors.primary.r));
      expect(AppColors.primary.r, greaterThan(AppColors.primary.g));
      // Dark enough to read as premium rather than as a toy, light enough
      // not to be the near-black "dark purple" the brief rules out.
      expect(_luminance(AppColors.primary), lessThan(0.15));
      expect(_luminance(AppColors.primary), greaterThan(0.03));
    });

    test('white text on the primary violet clears WCAG AA', () {
      expect(_contrast(AppColors.primary, const Color(0xFFFFFFFF)), greaterThanOrEqualTo(4.5));
    });

    test('the ground is lavender, and clearly so', () {
      // Violet must be visible, not a grey that happens to lean violet.
      expect(AppColors.background.b, greaterThan(AppColors.background.g));
      expect(AppColors.background.r, greaterThan(AppColors.background.g));
      // Still light enough to sit under a screen of products.
      expect(_luminance(AppColors.background), greaterThan(0.75));
    });

    test('cards are warm and lift off the lavender ground', () {
      // Warm: red above blue. Pure white on lavender is the combination that
      // reads clinical, which is the opposite of the intent.
      expect(AppColors.cardBackground.r, greaterThan(AppColors.cardBackground.b));
      expect(AppColors.cardBackground, isNot(AppColors.background));
      expect(_luminance(AppColors.cardBackground), greaterThan(_luminance(AppColors.background)));
    });

    test('section bands lift toward the light rather than darkening', () {
      // Depth from a section brightening, not from it casting a shadow
      // across the products.
      expect(_luminance(AppColors.surfaceSoft), greaterThan(_luminance(AppColors.background)));
    });

    test('nothing in the palette is black', () {
      // Explicit rule: black against lavender is a hard edge that cheapens
      // the whole screen.
      for (final colour in <Color>[
        AppColors.textPrimary,
        AppColors.textSecondary,
        AppColors.primary,
        AppColors.accent,
        AppColors.gold,
        AppColors.cart,
      ]) {
        expect(_luminance(colour), greaterThan(0.01), reason: '$colour is effectively black');
      }
      // Text is indigo-navy: blue must lead red and green.
      expect(AppColors.textPrimary.b, greaterThan(AppColors.textPrimary.g));
    });

    test('discount coral carries white badge text', () {
      // The badge renders at 10px bold, which is NOT "large text" under
      // WCAG, so it needs the full 4.5 rather than 3.0. A prettier, lighter
      // peach fails this - which is exactly why the badge colour is burnt.
      expect(_contrast(AppColors.accent, const Color(0xFFFFFFFF)), greaterThanOrEqualTo(4.5));
      // Warm: red leads.
      expect(AppColors.accent.r, greaterThan(AppColors.accent.b));
    });

    test('the ADD button is teal and readable, and is not the brand violet', () {
      // Deliberately its own colour so the basket action reads as its own
      // thing rather than as another primary button.
      expect(AppColors.cart, isNot(AppColors.primary));
      expect(AppColors.cart.g, greaterThan(AppColors.cart.r));
      expect(_contrast(AppColors.cart, const Color(0xFFFFFFFF)), greaterThanOrEqualTo(4.5));
    });

    test('gold is readable, not decorative', () {
      // A rating figure sits beside the stars, so the colour has to carry
      // text rather than only tint an icon.
      expect(_contrast(AppColors.gold, AppColors.cardBackground), greaterThanOrEqualTo(4.5));
      expect(AppColors.gold.r, greaterThan(AppColors.gold.b));
    });

    test('the roles stay distinguishable by hue', () {
      // Measured as HUE separation, not contrast ratio. Contrast is a
      // LUMINANCE comparison, and coral, gold and teal deliberately sit at
      // almost identical luminance because each was tuned to clear 4.5:1
      // against white - so a contrast check between them reports ~1.0 and
      // would read as "these colours are identical" when they are plainly
      // not. Hue is the property that actually decides whether a shopper can
      // tell a discount badge from a rating star.
      //
      // 20 degrees is the threshold. Coral and gold are the closest pair at
      // ~24 degrees; they are also separated by shape and context - a filled
      // badge on an image corner versus a star glyph in a text row - so the
      // colour is not carrying the distinction alone.
      final roles = <String, Color>{
        'primary': AppColors.primary,
        'accent': AppColors.accent,
        'gold': AppColors.gold,
        'cart': AppColors.cart,
      };

      final names = roles.keys.toList();
      for (var i = 0; i < names.length; i++) {
        for (var j = i + 1; j < names.length; j++) {
          final separation = _hueGap(roles[names[i]]!, roles[names[j]]!);
          expect(separation, greaterThanOrEqualTo(20.0),
              reason: '${names[i]} and ${names[j]} are only '
                  '${separation.toStringAsFixed(1)} degrees apart');
        }
      }
    });

    test('body text clears WCAG AA on the ground and on cards', () {
      expect(_contrast(AppColors.textPrimary, AppColors.background), greaterThanOrEqualTo(4.5));
      expect(_contrast(AppColors.textPrimary, AppColors.cardBackground), greaterThanOrEqualTo(4.5));
      expect(_contrast(AppColors.textSecondary, AppColors.cardBackground), greaterThanOrEqualTo(4.5));
      expect(_contrast(AppColors.textSecondary, AppColors.background), greaterThanOrEqualTo(4.5));
    });
  });
}
