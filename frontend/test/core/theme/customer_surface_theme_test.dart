import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/theme/app_theme.dart';
import 'package:gpstore/core/theme/customer_surface_theme.dart';

class _MemoryThemeStore implements CustomerThemePreferenceStore {
  _MemoryThemeStore({this.value, this.readFuture});

  String? value;
  final Future<String?>? readFuture;
  final List<String> writes = [];

  @override
  Future<String?> readThemeId() => readFuture ?? Future.value(value);

  @override
  Future<void> writeThemeId(String id) async {
    value = id;
    writes.add(id);
  }
}

double _contrast(Color a, Color b) {
  final lighter =
      a.computeLuminance() > b.computeLuminance() ? a : b;
  final darker = identical(lighter, a) ? b : a;
  return (lighter.computeLuminance() + 0.05) /
      (darker.computeLuminance() + 0.05);
}

void main() {
  test('exactly ten curated customer colour presets are available', () {
    expect(CustomerSurfaceThemes.all, hasLength(10));
    expect(
      CustomerSurfaceThemes.all.map((theme) => theme.id).toSet(),
      hasLength(10),
    );
    expect(
      CustomerSurfaceThemes.all.map((theme) => theme.ground).toSet(),
      hasLength(10),
    );
  });

  test('every preset keeps normal GP-STORE text accessible', () {
    for (final theme in CustomerSurfaceThemes.all) {
      expect(
        _contrast(theme.ground, AppColors.textPrimary),
        greaterThanOrEqualTo(4.5),
        reason: '${theme.label} must keep body text WCAG AA readable',
      );
    }
  });

  test('unknown saved theme safely falls back to sunshine', () {
    expect(
      CustomerSurfaceThemes.byId('something-from-a-future-build').id,
      CustomerSurfaceThemes.sunshine.id,
    );
  });

  test('saved choice restores and a new choice persists', () async {
    final store = _MemoryThemeStore(value: CustomerSurfaceThemes.blush.id);
    final controller = CustomerSurfaceThemeController(store);
    addTearDown(controller.dispose);

    await Future<void>.delayed(Duration.zero);
    expect(controller.state.id, CustomerSurfaceThemes.blush.id);

    await controller.select(CustomerSurfaceThemes.sky);
    expect(controller.state.id, CustomerSurfaceThemes.sky.id);
    expect(store.writes, [CustomerSurfaceThemes.sky.id]);
  });

  test('a tap wins over a slow cold-start restore', () async {
    final delayed = Completer<String?>();
    final store = _MemoryThemeStore(readFuture: delayed.future);
    final controller = CustomerSurfaceThemeController(store);
    addTearDown(controller.dispose);

    final selection = controller.select(CustomerSurfaceThemes.mint);
    delayed.complete(CustomerSurfaceThemes.blush.id);
    await selection;
    await Future<void>.delayed(Duration.zero);

    expect(controller.state.id, CustomerSurfaceThemes.mint.id);
  });
}
