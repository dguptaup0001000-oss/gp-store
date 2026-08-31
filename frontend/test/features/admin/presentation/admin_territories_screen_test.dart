import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/util/app_haptics.dart';
import 'package:gpstore/features/admin/data/territory_repository.dart';
import 'package:gpstore/features/admin/domain/territory_models.dart';
import 'package:gpstore/admin/shell/admin_destinations.dart';
import 'package:gpstore/features/admin/presentation/admin_providers.dart';
import 'package:gpstore/features/admin/presentation/admin_territories_screen.dart';

import '../../../support/test_api_client.dart';

class _FakeTerritoryRepository extends TerritoryRepository {
  _FakeTerritoryRepository({
    this.health,
    this.healthError,
    this.zones = const [],
    this.subzones = const [],
    this.subzonesError,
    this.healthHold,
  }) : super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  final TerritoryHealth? health;
  final Object? healthError;
  final Completer<TerritoryHealth>? healthHold;
  final List<TerritoryZone> zones;
  final List<TerritorySubzone> subzones;
  final Object? subzonesError;

  @override
  Future<TerritoryHealth> getHealth() {
    if (healthError != null) return Future.error(healthError!);
    if (healthHold != null) return healthHold!.future;
    return Future.value(health ?? const TerritoryHealth());
  }

  @override
  Future<List<TerritoryZone>> listZones() {
    return Future.value(zones);
  }

  @override
  Future<List<TerritorySubzone>> listSubzones() {
    if (subzonesError != null) return Future.error(subzonesError!);
    return Future.value(subzones);
  }
}

Widget _scope(TerritoryRepository repository, Widget home) {
  return ProviderScope(
    overrides: [territoryRepositoryProvider.overrideWithValue(repository)],
    child: MaterialApp(home: home),
  );
}

void main() {
  setUpAll(setUpFakeSecureStorage);
  setUp(() {
    AppHaptics.resetForTest();
    AppHaptics.enabled = false;
  });

  // Was a pump of the old card-list home screen. That screen is gone; the
  // console now navigates from AdminNav, so this asserts the same property
  // - Territories is reachable from the admin console, and still explains
  // itself - against the list the sidebar and drawer are both built from.
  test('the admin console can navigate to Territories', () {
    final destination =
        AdminNav.all.firstWhere((d) => d.label == 'Territories');
    expect(destination.description, 'Zones, riders, and map outlines');
    expect(destination.builder, isNotNull);
  });

  group('AdminTerritoriesScreen', () {
    testWidgets('shows a spinner while health is loading', (tester) async {
      final hold = Completer<TerritoryHealth>();
      await tester.pumpWidget(_scope(
        _FakeTerritoryRepository(healthHold: hold),
        const AdminTerritoriesScreen(),
      ));
      await tester.pump();
      expect(find.byType(CircularProgressIndicator), findsWidgets);
      hold.complete(const TerritoryHealth());
      await tester.pumpAndSettle();
    });

    testWidgets('shows the health failure and retries', (tester) async {
      await tester.pumpWidget(_scope(
        _FakeTerritoryRepository(healthError: Exception('backend down')),
        const AdminTerritoriesScreen(),
      ));
      await tester.pumpAndSettle();
      expect(find.textContaining("Couldn't load territory status"), findsOneWidget);
      // The shared AdminErrorState labels its retry "Try again". This
      // asserts the same property as before - a retry is offered - against
      // the console's one error state rather than a per-screen button.
      expect(find.text('Try again'), findsWidgets);
    });

    testWidgets('empty configuration is a real state, not a crash', (tester) async {
      tester.view.physicalSize = const Size(800, 4000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_scope(
        _FakeTerritoryRepository(
          health: const TerritoryHealth(
            expectedZones: 8,
            expectedSubzones: 26,
            problems: ['Expected 8 main zones, found 0.', 'Expected 26 subzones, found 0.'],
          ),
        ),
        const AdminTerritoriesScreen(),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Needs attention'), findsOneWidget);
      expect(find.text('0 / 8'), findsOneWidget);
      expect(find.text('0 / 26'), findsOneWidget);
      expect(find.textContaining('Nothing is configured yet'), findsOneWidget);
      expect(find.textContaining('No main zones yet'), findsOneWidget);
      expect(find.textContaining('No territories yet'), findsOneWidget);
      expect(find.textContaining('not a map'), findsWidgets);
    });

    testWidgets('lists zones and territories with outline status, not a polygon', (tester) async {
      tester.view.physicalSize = const Size(800, 4000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_scope(
        _FakeTerritoryRepository(
          health: const TerritoryHealth(
            zones: 1,
            expectedZones: 8,
            subzones: 1,
            expectedSubzones: 26,
            subzonesWithBoundary: 1,
            subzonesWithPrimaryPartner: 1,
            problems: ['Expected 8 main zones, found 1.'],
          ),
          zones: const [TerritoryZone(id: 7, code: 'Z7', name: 'Canal')],
          subzones: const [
            TerritorySubzone(
              id: 4,
              code: 'Z7B',
              name: 'Canal Colony',
              boundary: '[[28.61,77.20],[28.62,77.20],[28.62,77.21]]',
              primaryPartner: TerritoryPartnerRef(id: 9, name: 'Ravi'),
              zone: TerritoryZone(id: 7, code: 'Z7', name: 'Canal'),
            ),
          ],
        ),
        const AdminTerritoriesScreen(),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Z7 · Canal'), findsOneWidget);
      expect(find.text('Z7B · Canal Colony'), findsOneWidget);
      expect(find.textContaining('Outline stored (3 points)'), findsOneWidget);
      expect(find.textContaining('Rider: Ravi'), findsOneWidget);
    });

    testWidgets('a failed territory list can retry without hiding health', (tester) async {
      tester.view.physicalSize = const Size(800, 4000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_scope(
        _FakeTerritoryRepository(
          health: const TerritoryHealth(zones: 0, expectedZones: 8, subzones: 0, expectedSubzones: 26),
          subzonesError: Exception('lazy neighbours'),
        ),
        const AdminTerritoriesScreen(),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining("Couldn't load territories"), findsOneWidget);
      expect(find.text('Map status'), findsOneWidget);
    });
  });
}
