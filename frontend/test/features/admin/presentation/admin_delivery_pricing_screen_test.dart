import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import '../../../support/test_api_client.dart';
import 'package:gpstore/core/util/app_haptics.dart';
import 'package:gpstore/features/admin/data/delivery_pricing_repository.dart';
import 'package:gpstore/features/admin/domain/delivery_pricing_models.dart';
import 'package:gpstore/features/admin/presentation/admin_delivery_pricing_screen.dart';
import 'package:gpstore/admin/shell/admin_destinations.dart';
import 'package:gpstore/features/admin/presentation/admin_order_delivery_breakdown.dart';
import 'package:gpstore/features/admin/presentation/admin_providers.dart';

class _FakeDeliveryPricingRepository extends DeliveryPricingRepository {
  _FakeDeliveryPricingRepository({
    this.settings,
    this.settingsError,
    this.breakdown,
    this.breakdownError,
    Completer<DeliveryPricingSettings>? settingsHold,
  })  : settingsHold = settingsHold,
        super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  final DeliveryPricingSettings? settings;
  final Object? settingsError;
  final Completer<DeliveryPricingSettings>? settingsHold;
  DeliveryPricingSettings? lastSaved;
  final DeliveryOrderBreakdown? breakdown;
  final Object? breakdownError;

  @override
  Future<DeliveryPricingSettings> getSettings() {
    if (settingsError != null) return Future.error(settingsError!);
    if (settingsHold != null) return settingsHold!.future;
    return Future.value(settings!);
  }

  @override
  Future<DeliveryPricingSettings> saveSettings(DeliveryPricingSettings incoming) async {
    lastSaved = incoming;
    return incoming.copyWith(updatedAt: '2026-08-24T08:00:00', updatedBy: 'admin:1');
  }

  @override
  Future<DeliveryOrderBreakdown> getOrderBreakdown(int orderId) {
    if (breakdownError != null) return Future.error(breakdownError!);
    return Future.value(breakdown!);
  }
}

const _v1Settings = DeliveryPricingSettings(
  id: 1,
  distanceTier1Charge: 5,
  distanceTier1MaxKm: 1,
  distanceTier2Charge: 10,
  distanceTier2MaxKm: 2,
  additionalKmCharge: 5,
  freeWeightKg: 10,
  additionalWeightPerKg: 2,
  maximumWeightSurcharge: 20,
  freeDeliveryMultiplier: 3,
  roadDistanceFactor: 1,
  assumedWeightPerItemKg: 0,
  updatedAt: '2026-08-24T07:22:00',
  updatedBy: 'admin:9',
);

void main() {
  setUpAll(setUpFakeSecureStorage);
  setUp(() {
    AppHaptics.resetForTest();
    AppHaptics.enabled = false;
  });

  // Same substitution as the Territories test: the card-list home screen is
  // gone, so reachability is asserted against the navigation both the
  // sidebar and the drawer are built from.
  test('the admin console can navigate to Delivery Pricing', () {
    final destination =
        AdminNav.all.firstWhere((d) => d.label == 'Delivery Pricing');
    expect(destination.description, 'Distance, weight, and free-delivery rules');
    expect(destination.builder, isNotNull);
  });

  group('AdminDeliveryPricingScreen', () {
    testWidgets('shows a spinner while settings are loading', (tester) async {
      final hold = Completer<DeliveryPricingSettings>();
      final fake = _FakeDeliveryPricingRepository(settingsHold: hold);

      await tester.pumpWidget(_scope(fake, const AdminDeliveryPricingScreen()));
      await tester.pump();

      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      hold.complete(_v1Settings);
      await tester.pump();
    });

    testWidgets('shows the failure and retries', (tester) async {
      final fake = _FakeDeliveryPricingRepository(settingsError: Exception('backend down'));

      await tester.pumpWidget(_scope(fake, const AdminDeliveryPricingScreen()));
      await tester.pumpAndSettle();

      expect(find.textContaining("Couldn't load delivery pricing"), findsOneWidget);
      // The shared AdminErrorState labels its retry "Try again". This
      // asserts the same property as before - a retry is offered - against
      // the console's one error state rather than a per-screen button.
      expect(find.text('Try again'), findsOneWidget);
    });

    testWidgets('fills the form from the GET payload and saves those field names', (tester) async {
      tester.view.physicalSize = const Size(800, 4000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final fake = _FakeDeliveryPricingRepository(settings: _v1Settings);

      await tester.pumpWidget(_scope(fake, const AdminDeliveryPricingScreen()));
      await tester.pumpAndSettle();

      expect(find.text('Charge up to the first band (₹)'), findsOneWidget);
      expect(find.text('5'), findsWidgets);

      await tester.enterText(find.byType(TextFormField).first, '7');
      await tester.tap(find.text('Save pricing'));
      await tester.pumpAndSettle();

      expect(fake.lastSaved, isNotNull);
      expect(fake.lastSaved!.distanceTier1Charge, 7);
      expect(fake.lastSaved!.freeDeliveryMultiplier, 3);
      expect(find.text('Delivery pricing saved. New checkouts use these numbers.'), findsOneWidget);
    });
  });

  group('AdminOrderDeliveryBreakdownCard', () {
    testWidgets('renders stored figures for a current-system order', (tester) async {
      final fake = _FakeDeliveryPricingRepository(
        breakdown: const DeliveryOrderBreakdown(
          orderId: 41,
          pricedByCurrentSystem: true,
          distanceKm: 1.4,
          totalWeightKg: 3.25,
          distanceCharge: 10,
          weightCharge: 0,
          normalDeliveryCharge: 10,
          availableProfit: 90,
          freeDeliveryRequiredProfit: 30,
          freeDelivery: true,
          subsidy: 10,
          finalDeliveryCharge: 0,
          notes: 'used assumed weight on 1 item',
        ),
      );

      await tester.pumpWidget(_scope(fake, const Scaffold(body: AdminOrderDeliveryBreakdownCard(orderId: 41))));
      await tester.pumpAndSettle();

      expect(find.text('Final delivery charge'), findsOneWidget);
      expect(find.text('₹0.00'), findsWidgets);
      expect(find.text('Yes'), findsOneWidget);
      expect(find.text('used assumed weight on 1 item'), findsOneWidget);
      expect(find.textContaining('not recalculated'), findsOneWidget);
    });

    testWidgets('explains a pre-current-system order instead of a blank grid', (tester) async {
      final fake = _FakeDeliveryPricingRepository(
        breakdown: const DeliveryOrderBreakdown(
          orderId: 2,
          pricedByCurrentSystem: false,
          finalDeliveryCharge: 15,
        ),
      );

      await tester.pumpWidget(_scope(fake, const Scaffold(body: AdminOrderDeliveryBreakdownCard(orderId: 2))));
      await tester.pumpAndSettle();

      expect(
        find.textContaining('before the current delivery pricing system'),
        findsOneWidget,
      );
      expect(find.text('Final delivery charge'), findsNothing);
    });

    testWidgets('shows retry when the stored breakdown cannot be loaded', (tester) async {
      final fake = _FakeDeliveryPricingRepository(breakdownError: Exception('not found'));

      await tester.pumpWidget(_scope(fake, const Scaffold(body: AdminOrderDeliveryBreakdownCard(orderId: 9))));
      await tester.pumpAndSettle();

      expect(find.textContaining("Couldn't load the stored delivery breakdown"), findsOneWidget);
      expect(find.text('Try again'), findsOneWidget);
    });
  });
}

Widget _scope(DeliveryPricingRepository repository, Widget home) {
  return ProviderScope(
    overrides: [deliveryPricingRepositoryProvider.overrideWithValue(repository)],
    child: MaterialApp(home: home),
  );
}
