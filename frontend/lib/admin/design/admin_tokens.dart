import 'dart:ui' show FontFeature;

import 'package:flutter/material.dart';

/// GP-STORE Admin design tokens - the Emerald design language.
///
/// SEPARATE FROM core/theme/app_theme.dart ON PURPOSE. That file is the
/// customer shop's identity (deep violet #5B3FA8 with warm peach/cream
/// neutrals) and 47 customer files depend on it. Repainting it emerald to
/// restyle the admin console would restyle the shop with it. The admin APK
/// is a separate application after the customer/admin split, so it gets its
/// own tokens and leaves the shop alone.
///
/// GREEN IS THE ACCENT, NOT THE INTERFACE. The surface is white on slate.
/// Emerald marks brand, primary action, active navigation, and positive
/// figures. A console an operator stares at for a full shift must stay calm;
/// saturating it defeats the point and makes real status colour meaningless.
class AdminColors {
  const AdminColors._();

  // Brand
  static const primary = Color(0xFF16A34A);
  static const primaryDark = Color(0xFF15803D);
  static const primaryDeep = Color(0xFF166534);
  static const primaryLight = Color(0xFFDCFCE7);
  static const primaryFaint = Color(0xFFF0FDF4);

  // Surface
  static const background = Color(0xFFF8FAFC);
  static const surface = Color(0xFFFFFFFF);
  static const border = Color(0xFFE2E8F0);
  static const borderStrong = Color(0xFFCBD5E1);

  // Text
  static const textPrimary = Color(0xFF0F172A);
  static const textSecondary = Color(0xFF64748B);
  static const textMuted = Color(0xFF94A3B8);
  static const textOnPrimary = Color(0xFFFFFFFF);

  // Semantic. Deliberately distinct from the brand green so "success" and
  // "this is a button" never read as the same thing at a glance - which
  // means success is NOT #16A34A. A deeper, bluer green: on the pale
  // successBg it also clears text contrast by a wide margin, which the
  // brand green does not.
  static const success = Color(0xFF047857);
  static const successBg = Color(0xFFDCFCE7);
  static const warning = Color(0xFFF59E0B);
  static const warningBg = Color(0xFFFEF3C7);
  static const danger = Color(0xFFDC2626);
  static const dangerBg = Color(0xFFFEE2E2);
  static const info = Color(0xFF2563EB);
  static const infoBg = Color(0xFFDBEAFE);
  static const neutralBg = Color(0xFFF1F5F9);

  /// Sidebar is deep slate, not green. A full-height emerald rail would
  /// dominate every screen and leave nothing for the accent to say.
  static const sidebar = Color(0xFF0F172A);
  static const sidebarHover = Color(0xFF1E293B);
  static const sidebarText = Color(0xFF94A3B8);
  static const sidebarTextActive = Color(0xFFFFFFFF);
  static const sidebarHeading = Color(0xFF64748B);
}

/// 8px spacing scale. Every gap in the admin app comes from here so spacing
/// stays consistent without each screen inventing its own numbers.
class AdminSpacing {
  const AdminSpacing._();

  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 24;
  static const double xxl = 32;
  static const double xxxl = 48;
}

class AdminRadius {
  const AdminRadius._();

  static const double sm = 6;
  static const double md = 8;
  static const double lg = 12;
  static const double xl = 16;
  static const double pill = 999;

  static const BorderRadius card = BorderRadius.all(Radius.circular(lg));
  static const BorderRadius control = BorderRadius.all(Radius.circular(md));
  static const BorderRadius badge = BorderRadius.all(Radius.circular(pill));
}

class AdminShadows {
  const AdminShadows._();

  /// Subtle by design. A dashboard of heavily shadowed cards reads as a toy;
  /// a hairline border plus a barely-there lift reads as a tool.
  static const List<BoxShadow> card = [
    BoxShadow(
      color: Color(0x0A0F172A),
      blurRadius: 3,
      offset: Offset(0, 1),
    ),
  ];

  static const List<BoxShadow> raised = [
    BoxShadow(
      color: Color(0x140F172A),
      blurRadius: 12,
      offset: Offset(0, 4),
    ),
  ];
}

/// Type scale. Tabular figures on anything numeric so money and counts line
/// up in columns instead of jittering as digits change.
class AdminText {
  const AdminText._();

  static const _tabular = [FontFeature.tabularFigures()];

  static const TextStyle pageTitle = TextStyle(
    fontSize: 22,
    fontWeight: FontWeight.w700,
    height: 1.25,
    color: AdminColors.textPrimary,
  );

  static const TextStyle sectionTitle = TextStyle(
    fontSize: 16,
    fontWeight: FontWeight.w600,
    height: 1.3,
    color: AdminColors.textPrimary,
  );

  static const TextStyle body = TextStyle(
    fontSize: 14,
    fontWeight: FontWeight.w400,
    height: 1.45,
    color: AdminColors.textPrimary,
  );

  static const TextStyle bodyMuted = TextStyle(
    fontSize: 14,
    fontWeight: FontWeight.w400,
    height: 1.45,
    color: AdminColors.textSecondary,
  );

  static const TextStyle caption = TextStyle(
    fontSize: 12,
    fontWeight: FontWeight.w400,
    height: 1.35,
    color: AdminColors.textSecondary,
  );

  /// Uppercase group headings in the sidebar and above table sections.
  static const TextStyle overline = TextStyle(
    fontSize: 11,
    fontWeight: FontWeight.w600,
    letterSpacing: 0.8,
    color: AdminColors.sidebarHeading,
  );

  static const TextStyle metric = TextStyle(
    fontSize: 26,
    fontWeight: FontWeight.w700,
    height: 1.1,
    color: AdminColors.textPrimary,
    fontFeatures: _tabular,
  );

  static const TextStyle metricLarge = TextStyle(
    fontSize: 32,
    fontWeight: FontWeight.w700,
    height: 1.1,
    color: AdminColors.textPrimary,
    fontFeatures: _tabular,
  );

  static const TextStyle numeric = TextStyle(
    fontSize: 14,
    fontWeight: FontWeight.w600,
    color: AdminColors.textPrimary,
    fontFeatures: _tabular,
  );
}

/// Layout breakpoints.
///
/// THIS SHIPS AS AN ANDROID APK, so the phone layout is the real one and the
/// desktop sidebar is the exception, not the other way round. Below
/// [expanded] there is no permanent rail at all - navigation is a drawer,
/// because a 240px sidebar on a 360px phone leaves 120px of content.
class AdminBreakpoints {
  const AdminBreakpoints._();

  /// At or above this width a permanent sidebar is affordable.
  static const double expanded = 1024;

  /// Tablet-ish: two-column content, still a drawer for navigation.
  static const double medium = 720;

  static bool isExpanded(double width) => width >= expanded;
  static bool isMedium(double width) => width >= medium && width < expanded;
  static bool isCompact(double width) => width < medium;
}
