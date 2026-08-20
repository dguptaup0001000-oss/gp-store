import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

/// GP-Store's brand identity: a four-colour system on a cool near-white
/// ground, deliberately not the green-on-white of every other grocery app
/// and deliberately not amber-led either.
///
/// EACH COLOUR HAS ONE JOB. That constraint is the whole point - four
/// accents used at random reads as chaos, not as premium. Anything new
/// should reuse the role below rather than introduce a fifth colour:
///
///   primary   (royal blue)  - primary buttons, selected tabs, active nav,
///                             links, the selected category. The "you are
///                             here / this is the action" colour.
///   secondary (mint)        - success, in-stock, delivery progress, any
///                             positive state. Never decorative.
///   accent    (coral)       - discounts, offers, promo badges. Reserved for
///                             money-off, so a coral pill always means the
///                             same thing to a shopper.
///   highlight (lavender)    - category accents, recommendations, special
///                             sections. The "curated for you" colour.
///
/// Amber was the previous secondary and is gone: it read as a yellow-led
/// brand once it reached buttons and chips, which is not the identity.
class AppColors {
  AppColors._();

  static const primary = Color(0xFF2563EB); // royal / electric blue
  static const secondary = Color(0xFF10B981); // mint / emerald - success, in stock
  static const accent = Color(0xFFF97316); // coral / orange - discounts, offers
  static const highlight = Color(0xFF8B5CF6); // lavender - categories, curation

  /// Cart and basket actions specifically - ADD, the quantity stepper, the
  /// cart bar. Given its own name rather than reusing [secondary] because
  /// "this succeeded" and "this is a shopping action" are different ideas
  /// that happen to share a hue family; separating them means the cart can
  /// be re-tinted later without repainting every success state.
  static const cart = Color(0xFF0D9488); // teal

  /// Soft accent washes for category tiles and section backgrounds. Used as
  /// large calm surfaces, never as text or icon colours - they do not carry
  /// enough contrast for either.
  static const peach = Color(0xFFFFEDE3);
  static const cream = Color(0xFFFFF7EC);
  static const mist = Color(0xFFEFF3FF);

  static const background = Color(0xFFF4F8FF); // very light cool blue-grey
  static const cardBackground = Color(0xFFFFFFFF); // white cards lift off the ground
  static const error = Color(0xFFD32F2F);

  /// Kept as its own name rather than aliased to [secondary]: "the brand's
  /// second colour" and "this operation succeeded" are different ideas, and
  /// collapsing them means a future brand change silently repaints every
  /// success state. They happen to be the same value today.
  static const success = Color(0xFF10B981);

  static const textPrimary = Color(0xFF172033); // deep navy
  static const textSecondary = Color(0xFF64748B); // slate grey

  /// Tinted surfaces for section accents - the 12%-opacity wash used behind
  /// category chips and section headers. Derived here so no screen invents
  /// its own opacity and drifts.
  static Color tint(Color base) => base.withValues(alpha: 0.12);
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
        shadowColor: Colors.black.withValues(alpha: 0.06),
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
