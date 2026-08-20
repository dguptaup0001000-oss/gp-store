import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

/// GP-Store's brand identity: soft lavender ground, deep violet actions,
/// warm cream surfaces.
///
/// SIX ROLES, EACH WITH ONE JOB. That constraint is what separates a palette
/// from noise - six colours used at random reads as childish, which is the
/// opposite of the intent. Anything new reuses a role below rather than
/// introducing a seventh:
///
///   background  (soft lavender)  - the ground the whole app sits on
///   surfaceSoft (paler violet)   - section bands, for depth without a
///                                  second hue
///   primary     (deep violet)    - selected category, active nav, links,
///                                  primary buttons. The identity.
///   cart        (teal)           - ADD, the quantity stepper, the cart bar.
///                                  Deliberately NOT the brand violet: the
///                                  basket action should read as its own
///                                  thing, and teal is the one cool colour
///                                  that sits beside violet without fighting
///                                  it.
///   accent      (burnt coral)    - discounts and offers, nothing else, so a
///                                  coral pill always means money off.
///   gold        (warm amber)     - ratings, stars, small premium accents.
///   cardBackground (warm cream)  - product cards and floating surfaces.
///
/// WARM, NOT COLD. The cream card and the amber accent are what stop a
/// violet app reading as clinical - lavender on pure white is a hospital,
/// lavender on warm cream is a shop.
///
/// NO BLACK ANYWHERE. Text is a deep indigo-navy that belongs to the same
/// family as the background, so type sits in the palette rather than being
/// punched through it.
///
/// Every colour that carries text has been checked for contrast, and those
/// checks live in test/core/theme/app_colors_test.dart rather than in a
/// designer's memory - "let's brighten the violet" is exactly the change
/// that silently breaks readability.
class AppColors {
  AppColors._();

  /// Deep violet. 7.7:1 against white, so white text on a violet button
  /// clears WCAG AA comfortably at any size.
  ///
  /// Deep rather than bright: a saturated violet at this size reads as a
  /// toy, and a dark purple reads as luxury-goods rather than groceries.
  static const primary = Color(0xFF5B3FA8);

  /// A lighter violet for pressed states and washes that must stay violet
  /// without going black.
  static const primarySoft = Color(0xFF8163C9);

  /// Positive states - in stock, delivery progress, success. Shares the
  /// cart's family so "available" and "add it" feel related.
  static const secondary = Color(0xFF0F766E);

  /// Discounts and offers. Burnt coral rather than a pale peach because it
  /// carries white badge text at 4.9:1 - a prettier, lighter coral would
  /// fail contrast at the 10px the badge actually renders at.
  static const accent = Color(0xFFC4472B);

  /// Ratings, stars, and small premium accents. 4.9:1 on white, so a rating
  /// figure beside the stars is readable rather than decorative.
  static const gold = Color(0xFFA16207);

  /// Section accents and curated rails.
  static const highlight = primary;

  /// Cart and basket actions - ADD, the quantity stepper, the cart bar.
  ///
  /// Its own name rather than an alias so baskets can be re-tinted later
  /// without repainting every primary button.
  static const cart = Color(0xFF0F766E);

  /// Soft washes for category tiles and promotional bands. Large calm
  /// surfaces only - none of these carry enough contrast for text or icons.
  static const peach = Color(0xFFFCE8E0);
  static const cream = Color(0xFFFDF3E3);

  /// Warm ivory - the "premium section" surface, for bands that should feel
  /// like paper rather than a tint.
  static const ivory = Color(0xFFFFFAF2);

  /// Pale violet wash, the lavender-side counterpart to [peach].
  static const mist = Color(0xFFEDE7FA);

  /// The ground. Clearly lavender rather than a grey that happens to lean
  /// violet, but calm enough to sit under a screen of product photography
  /// all day without tiring the eye.
  static const background = Color(0xFFEFEBFA);

  /// A paler violet for section bands. Lighter than [background] on purpose:
  /// depth comes from a section lifting toward the light, not from it
  /// darkening, which would read as a shadow across the products.
  static const surfaceSoft = Color(0xFFF7F4FD);

  /// Cards and floating surfaces. Warm white, not pure white - pure white on
  /// lavender is the combination that looks clinical.
  static const cardBackground = Color(0xFFFFFCF8);

  static const error = Color(0xFFC0392B);

  /// Kept as its own name rather than aliased to [secondary]: "the brand's
  /// second colour" and "this operation succeeded" are different ideas, and
  /// collapsing them means a future brand change silently repaints every
  /// success state.
  static const success = Color(0xFF0F766E);

  /// Deep indigo-navy. Explicitly NOT black: black against lavender is a
  /// hard edge that makes the whole screen feel cheaper, and this sits in
  /// the same family as the background while still reading at 14.8:1.
  static const textPrimary = Color(0xFF221F41);

  /// Muted violet-grey for secondary type. 4.6:1 on cream, so it stays
  /// readable rather than becoming decoration.
  static const textSecondary = Color(0xFF615A80);

  /// A hairline where two light surfaces meet and a shadow would be too
  /// heavy. Tinted violet so it belongs to the palette rather than reading
  /// as a stray pencil line.
  static const divider = Color(0xFFE2DCF2);

  /// The 12%-opacity wash used behind category chips and section headers.
  /// Derived here so no screen invents its own opacity and drifts.
  static Color tint(Color base) => base.withValues(alpha: 0.12);
}

/// Depth, as a small fixed vocabulary.
///
/// GP-Store's "premium" feel comes from products looking like they physically
/// sit inside the interface, and physical objects cast TWO shadows, not one:
/// a tight dark contact shadow where the object meets the surface, and a
/// wide soft ambient shadow further out. Material's default single shadow
/// reads as a flat sticker by comparison, which is why these are hand-built
/// rather than an `elevation:` number.
///
/// EVERY EFFECT HERE IS A BoxShadow. That is deliberate and it is the whole
/// performance story: Skia draws a blurred rounded rect directly, with no
/// intermediate layer. The alternatives that look similar - BackdropFilter,
/// ImageFiltered, the Opacity widget - each force a saveLayer, which means
/// allocating and compositing an offscreen buffer per widget per frame. In a
/// scrolling grid of product cards that is exactly how a design pass turns
/// into jank. No blur filters, no WebGL, no 3D assets: shadows, transforms
/// and alpha only.
class AppElevation {
  AppElevation._();

  /// A card at rest. Sits on the page, clearly above the background.
  static const card = <BoxShadow>[
    BoxShadow(color: Color(0x0F221F41), blurRadius: 2, offset: Offset(0, 1)),
    BoxShadow(color: Color(0x14221F41), blurRadius: 12, offset: Offset(0, 4)),
  ];

  /// A card under the finger. Shadows tighten and pull IN rather than
  /// disappearing - a pressed object moves closer to the surface, so its
  /// contact shadow gets smaller and darker, not lighter.
  static const cardPressed = <BoxShadow>[
    BoxShadow(color: Color(0x14221F41), blurRadius: 1, offset: Offset(0, 1)),
    BoxShadow(color: Color(0x0D221F41), blurRadius: 4, offset: Offset(0, 1)),
  ];

  /// The product itself, floating above the card surface.
  ///
  /// Offset further down and blurred wider than the card's own shadow, so the
  /// product reads as a separate object resting ON the card rather than
  /// printed onto it. This is the single effect that does most of the work.
  static const product = <BoxShadow>[
    BoxShadow(color: Color(0x1A221F41), blurRadius: 18, offset: Offset(0, 8)),
  ];

  /// Category icons and other small tiles - the same idea, scaled down.
  static const tile = <BoxShadow>[
    BoxShadow(color: Color(0x12221F41), blurRadius: 8, offset: Offset(0, 3)),
  ];
}

/// Shared corner-radius constants so every screen rounds consistently.
class AppRadius {
  AppRadius._();

  static const sm = 8.0;
  static const md = 12.0;
  static const lg = 16.0;
}

/// Shared spacing constants so every screen paces consistently.
class AppSpacing {
  AppSpacing._();

  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 16.0;
  static const lg = 24.0;
  static const xl = 32.0;
}

class AppTheme {
  AppTheme._();

  static ThemeData get light {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: AppColors.primary,
      brightness: Brightness.light,
      primary: AppColors.primary,
      secondary: AppColors.secondary,
      error: AppColors.error,
      surface: AppColors.background,
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: AppColors.background,
      fontFamily: 'Roboto',

      // Material's default divider is a neutral grey that reads as a stray
      // pencil line against the mint ground. This one is the ground's own
      // hue, darkened - a seam rather than a rule.
      dividerColor: AppColors.divider,
      dividerTheme: const DividerThemeData(color: AppColors.divider, thickness: 1, space: 1),

      // White, not the mint ground: the nav bar is a surface the page scrolls
      // under, and giving it the same colour as the page removes the edge
      // that says so. The green selected state needs a white field to read
      // against at 12px label size.
      bottomNavigationBarTheme: const BottomNavigationBarThemeData(
        // Warm cream, not the lavender ground: the nav is a surface the page
        // scrolls UNDER, and matching the page colour removes the edge that
        // says so.
        backgroundColor: AppColors.cardBackground,
        selectedItemColor: AppColors.primary,
        unselectedItemColor: AppColors.textSecondary,
        selectedLabelStyle: TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
        unselectedLabelStyle: TextStyle(fontSize: 12),
        type: BottomNavigationBarType.fixed,
        elevation: 8,
      ),

      textTheme: const TextTheme(
        headlineMedium: TextStyle(color: AppColors.textPrimary, fontWeight: FontWeight.w700),
        headlineSmall: TextStyle(color: AppColors.textPrimary, fontWeight: FontWeight.w700),
        titleLarge: TextStyle(color: AppColors.textPrimary, fontWeight: FontWeight.w600),
        titleMedium: TextStyle(color: AppColors.textPrimary, fontWeight: FontWeight.w600),
        bodyLarge: TextStyle(color: AppColors.textPrimary),
        bodyMedium: TextStyle(color: AppColors.textSecondary),
        labelLarge: TextStyle(color: AppColors.textPrimary, fontWeight: FontWeight.w600),
      ),

      appBarTheme: const AppBarTheme(
        backgroundColor: AppColors.background,
        foregroundColor: AppColors.textPrimary,
        elevation: 0,
        surfaceTintColor: Colors.transparent,
        centerTitle: false,
      ),

      cardTheme: CardThemeData(
        color: AppColors.cardBackground,
        elevation: 1,
        // The shadow is tinted with the text indigo rather than pure black,
        // so a card's edge belongs to the palette instead of punching a grey
        // hole in the lavender.
        shadowColor: AppColors.textPrimary.withValues(alpha: 0.08),
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.lg)),
      ),

      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: AppColors.primary,
          foregroundColor: Colors.white,
          minimumSize: const Size.fromHeight(48),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.md)),
          elevation: 0,
        ),
      ),

      // Material's default outlined button takes its foreground from the
      // colour scheme but leaves the border a neutral grey, so a green
      // secondary action ended up outlined in grey. Stating both keeps the
      // pair together.
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.primary,
          side: const BorderSide(color: AppColors.primary),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.md)),
        ),
      ),

      // Search boxes and text fields sit on the warm cream, giving them a
      // clear edge against the lavender ground without needing a border.
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: AppColors.cardBackground,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
          borderSide: BorderSide.none,
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      ),

      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
        },
      ),
    );
  }
}
