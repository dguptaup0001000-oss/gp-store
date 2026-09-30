import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'app_theme.dart';

/// The customer-facing "paper" behind GP-STORE's marketplace.
///
/// Branding stays fixed: the dark green header/navigation, yellow selected
/// navigation, buttons, text and product photography do not change. A theme
/// only changes broad light surfaces so customers can personalise the app
/// without creating ten different visual identities or ten accessibility
/// problems.
class CustomerSurfaceTheme {
  const CustomerSurfaceTheme({
    required this.id,
    required this.label,
    required this.ground,
  });

  final String id;
  final String label;
  final Color ground;
}

/// Ten curated presets rather than an unrestricted colour picker.
///
/// Every swatch is deliberately light enough to keep GP-STORE's normal dark
/// text readable. Product cards remain white, so merchant photography and
/// prices do not inherit a colour cast.
class CustomerSurfaceThemes {
  CustomerSurfaceThemes._();

  static const sunshine = CustomerSurfaceTheme(
    id: 'sunshine',
    label: 'Sunshine',
    ground: Color(0xFFFFF45A),
  );
  static const blush = CustomerSurfaceTheme(
    id: 'blush',
    label: 'Blush',
    ground: Color(0xFFFFD5E2),
  );
  static const sky = CustomerSurfaceTheme(
    id: 'sky',
    label: 'Sky',
    ground: Color(0xFFD8EFFF),
  );
  static const mint = CustomerSurfaceTheme(
    id: 'mint',
    label: 'Mint',
    ground: Color(0xFFDDF6E8),
  );
  static const lavender = CustomerSurfaceTheme(
    id: 'lavender',
    label: 'Lavender',
    ground: Color(0xFFE9E2FF),
  );
  static const peach = CustomerSurfaceTheme(
    id: 'peach',
    label: 'Peach',
    ground: Color(0xFFFFE0CF),
  );
  static const cream = CustomerSurfaceTheme(
    id: 'cream',
    label: 'Cream',
    ground: Color(0xFFFFF3D6),
  );
  static const silver = CustomerSurfaceTheme(
    id: 'silver',
    label: 'Silver',
    ground: Color(0xFFEEF1F3),
  );
  static const sage = CustomerSurfaceTheme(
    id: 'sage',
    label: 'Sage',
    ground: Color(0xFFE4EFD9),
  );
  static const white = CustomerSurfaceTheme(
    id: 'white',
    label: 'White',
    ground: AppColors.background,
  );

  static const all = <CustomerSurfaceTheme>[
    sunshine,
    blush,
    sky,
    mint,
    lavender,
    peach,
    cream,
    silver,
    sage,
    white,
  ];

  static CustomerSurfaceTheme byId(String? id) {
    if (id == null) return sunshine;
    for (final theme in all) {
      if (theme.id == id) return theme;
    }
    return sunshine;
  }
}

/// Tiny persistence seam so the controller can be tested without Android
/// storage and can later be backed by an account preference without changing
/// any screen.
abstract class CustomerThemePreferenceStore {
  Future<String?> readThemeId();
  Future<void> writeThemeId(String id);
}

/// Uses the storage plugin GP-STORE already ships.
///
/// A colour preference is not secret; secure storage is used here simply to
/// avoid adding another native persistence dependency for one tiny setting.
/// This key is isolated from authentication tokens.
class SecureCustomerThemePreferenceStore
    implements CustomerThemePreferenceStore {
  SecureCustomerThemePreferenceStore({
    FlutterSecureStorage storage = const FlutterSecureStorage(
      aOptions: AndroidOptions(
        resetOnError: true,
        encryptedSharedPreferences: true,
      ),
    ),
  }) : _storage = storage;

  static const _key = 'customer_surface_theme_v1';
  final FlutterSecureStorage _storage;

  @override
  Future<String?> readThemeId() => _storage.read(key: _key);

  @override
  Future<void> writeThemeId(String id) => _storage.write(key: _key, value: id);
}

final customerThemePreferenceStoreProvider =
    Provider<CustomerThemePreferenceStore>(
  (ref) => SecureCustomerThemePreferenceStore(),
);

class CustomerSurfaceThemeController
    extends StateNotifier<CustomerSurfaceTheme> {
  CustomerSurfaceThemeController(this._store)
      : super(CustomerSurfaceThemes.sunshine) {
    unawaited(_restore());
  }

  final CustomerThemePreferenceStore _store;

  // Prevent an async cold-start read from overwriting a colour the customer
  // selected while that read was still in flight.
  int _selectionVersion = 0;

  Future<void> _restore() async {
    final versionAtStart = _selectionVersion;
    try {
      final saved = await _store.readThemeId();
      if (versionAtStart != _selectionVersion) return;
      state = CustomerSurfaceThemes.byId(saved);
    } catch (_) {
      // Theme persistence is cosmetic. A storage failure must never block the
      // home screen; the default remains usable and the next selection retries.
    }
  }

  Future<void> select(CustomerSurfaceTheme theme) async {
    _selectionVersion++;
    if (state.id == theme.id) return;

    // Paint first. Storage happens after the frame so choosing a colour feels
    // instant even on a phone whose keystore is slow.
    state = theme;
    try {
      await _store.writeThemeId(theme.id);
    } catch (_) {
      // Keep the session's chosen colour. Failure to remember it across a
      // restart is preferable to snapping the UI back after the tap.
    }
  }
}

final customerSurfaceThemeProvider = StateNotifierProvider<
    CustomerSurfaceThemeController, CustomerSurfaceTheme>((ref) {
  return CustomerSurfaceThemeController(
    ref.watch(customerThemePreferenceStoreProvider),
  );
});
