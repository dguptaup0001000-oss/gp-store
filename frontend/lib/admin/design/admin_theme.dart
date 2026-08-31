import 'package:flutter/material.dart';

import 'admin_tokens.dart';

/// The admin console's Material theme.
///
/// THIS IS THE LEVER THAT RESTYLES TWENTY-FIVE SCREENS. Every one of them
/// builds a Scaffold, an AppBar, Cards, buttons, text fields and dialogs from
/// Material defaults, so stating those defaults once here re-skins all of
/// them without touching a single screen's widget tree - and without any risk
/// of breaking behaviour that a screen-by-screen rewrite would carry.
///
/// SEPARATE FROM AppTheme ON PURPOSE, for the same reason admin_tokens.dart
/// is separate from app_theme.dart: AppTheme is the customer shop's identity
/// (deep violet on warm cream) and dozens of customer files depend on it.
/// The admin APK is a different application; it gets a different theme and
/// leaves the shop alone.
///
/// GREEN IS THE ACCENT, NOT THE INTERFACE. Primary action, active state and
/// positive figures are emerald; everything else is white on slate. An
/// operator stares at this for a whole shift, and a saturated console both
/// tires the eye and leaves no colour free to mean "this needs attention".
class AdminTheme {
  AdminTheme._();

  static ThemeData get light {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: AdminColors.primary,
      brightness: Brightness.light,
      primary: AdminColors.primary,
      onPrimary: AdminColors.textOnPrimary,
      secondary: AdminColors.primaryDark,
      error: AdminColors.danger,
      surface: AdminColors.surface,
      onSurface: AdminColors.textPrimary,
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: AdminColors.background,
      fontFamily: 'Roboto',

      dividerColor: AdminColors.border,
      dividerTheme: const DividerThemeData(
        color: AdminColors.border,
        thickness: 1,
        space: 1,
      ),

      // Bars are WHITE while the page is slate, so the header keeps an edge
      // the content scrolls under. Matching them would erase the boundary
      // that tells you which part of the screen is fixed.
      appBarTheme: const AppBarTheme(
        backgroundColor: AdminColors.surface,
        foregroundColor: AdminColors.textPrimary,
        surfaceTintColor: AdminColors.surface,
        elevation: 0,
        scrolledUnderElevation: 0.5,
        centerTitle: false,
        titleTextStyle: TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.w600,
          color: AdminColors.textPrimary,
        ),
        shape: Border(bottom: BorderSide(color: AdminColors.border)),
      ),

      // A hairline border and almost no lift. A console of heavily shadowed
      // cards reads as a toy; the border is what separates panels here.
      cardTheme: const CardThemeData(
        color: AdminColors.surface,
        elevation: 0,
        surfaceTintColor: Colors.transparent,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: AdminRadius.card,
          side: BorderSide(color: AdminColors.border),
        ),
      ),

      textTheme: const TextTheme(
        headlineMedium: TextStyle(
            color: AdminColors.textPrimary, fontWeight: FontWeight.w700),
        headlineSmall: TextStyle(
            color: AdminColors.textPrimary, fontWeight: FontWeight.w700),
        titleLarge: TextStyle(
            color: AdminColors.textPrimary, fontWeight: FontWeight.w600),
        titleMedium: TextStyle(
            color: AdminColors.textPrimary, fontWeight: FontWeight.w600),
        bodyLarge: TextStyle(color: AdminColors.textPrimary),
        bodyMedium: TextStyle(color: AdminColors.textSecondary),
        labelLarge: TextStyle(
            color: AdminColors.textPrimary, fontWeight: FontWeight.w600),
      ),

      listTileTheme: const ListTileThemeData(
        iconColor: AdminColors.textSecondary,
        textColor: AdminColors.textPrimary,
      ),

      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: AdminColors.primary,
          foregroundColor: AdminColors.textOnPrimary,
          minimumSize: const Size.fromHeight(46),
          shape: const RoundedRectangleBorder(borderRadius: AdminRadius.control),
          elevation: 0,
        ),
      ),

      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: AdminColors.primary,
          foregroundColor: AdminColors.textOnPrimary,
          minimumSize: const Size.fromHeight(46),
          shape: const RoundedRectangleBorder(borderRadius: AdminRadius.control),
          elevation: 0,
        ),
      ),

      // Material leaves an outlined button's border neutral grey even when
      // its foreground is themed, so a green secondary action ends up in a
      // grey box. Stating both keeps the pair together.
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: AdminColors.primaryDark,
          side: const BorderSide(color: AdminColors.borderStrong),
          minimumSize: const Size.fromHeight(46),
          shape: const RoundedRectangleBorder(borderRadius: AdminRadius.control),
        ),
      ),

      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(foregroundColor: AdminColors.primaryDark),
      ),

      // VISIBLE BORDERS, unlike the shop's borderless filled fields. This is
      // a data-entry console - a shopkeeper typing a price into a form needs
      // to see where the field is, and a focused field must be unmistakable.
      inputDecorationTheme: const InputDecorationTheme(
        filled: true,
        fillColor: AdminColors.surface,
        border: OutlineInputBorder(
          borderRadius: AdminRadius.control,
          borderSide: BorderSide(color: AdminColors.border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: AdminRadius.control,
          borderSide: BorderSide(color: AdminColors.border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: AdminRadius.control,
          borderSide: BorderSide(color: AdminColors.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: AdminRadius.control,
          borderSide: BorderSide(color: AdminColors.danger),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: AdminRadius.control,
          borderSide: BorderSide(color: AdminColors.danger, width: 1.5),
        ),
        labelStyle: TextStyle(color: AdminColors.textSecondary),
        hintStyle: TextStyle(color: AdminColors.textMuted),
        contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 14),
      ),

      chipTheme: const ChipThemeData(
        backgroundColor: AdminColors.neutralBg,
        selectedColor: AdminColors.primaryLight,
        side: BorderSide(color: AdminColors.border),
        labelStyle: TextStyle(
          fontSize: 13,
          color: AdminColors.textPrimary,
        ),
        shape: RoundedRectangleBorder(borderRadius: AdminRadius.badge),
      ),

      dialogTheme: DialogThemeData(
        backgroundColor: AdminColors.surface,
        surfaceTintColor: AdminColors.surface,
        elevation: 8,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AdminRadius.xl),
        ),
        titleTextStyle: const TextStyle(
          fontSize: 17,
          fontWeight: FontWeight.w600,
          color: AdminColors.textPrimary,
        ),
      ),

      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: AdminColors.surface,
        surfaceTintColor: AdminColors.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(
            top: Radius.circular(AdminRadius.xl),
          ),
        ),
      ),

      snackBarTheme: SnackBarThemeData(
        backgroundColor: AdminColors.textPrimary,
        contentTextStyle: const TextStyle(color: AdminColors.textOnPrimary),
        behavior: SnackBarBehavior.floating,
        shape: const RoundedRectangleBorder(borderRadius: AdminRadius.control),
      ),

      floatingActionButtonTheme: const FloatingActionButtonThemeData(
        backgroundColor: AdminColors.primary,
        foregroundColor: AdminColors.textOnPrimary,
      ),

      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: AdminColors.primary,
        linearTrackColor: AdminColors.neutralBg,
      ),

      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) =>
            states.contains(WidgetState.selected)
                ? AdminColors.textOnPrimary
                : AdminColors.surface),
        trackColor: WidgetStateProperty.resolveWith((states) =>
            states.contains(WidgetState.selected)
                ? AdminColors.primary
                : AdminColors.borderStrong),
      ),

      tabBarTheme: const TabBarThemeData(
        labelColor: AdminColors.primaryDeep,
        unselectedLabelColor: AdminColors.textSecondary,
        indicatorColor: AdminColors.primary,
        dividerColor: AdminColors.border,
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
