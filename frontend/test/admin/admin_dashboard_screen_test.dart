import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/dashboard/admin_dashboard_screen.dart';
import 'package:gpstore/features/admin/domain/analytics_models.dart';
import 'package:gpstore/admin/dashboard/admin_live_clock_panel.dart';
import 'package:gpstore/features/admin/domain/presence_model.dart';
import 'package:gpstore/admin/operations/store_operations_models.dart';
import 'package:gpstore/admin/operations/store_operations_providers.dart';
import 'package:gpstore/features/admin/presentation/admin_providers.dart';

/// The dashboard's contract with the operator.
///
/// Overrides the LEAF providers rather than faking the repository, because
/// what is under test is what the screen does with an answer - not how the
/// answer was fetched. It also means one panel can be made to fail while the
/// others succeed, which is the property that actually matters here.
void main() {
  const summary = SalesSummary(
    periodDays: 30,
    revenue: 145000,
    orderCount: 312,
    cancelledCount: 7,
    averageOrderValue: 464.74,
    previousRevenue: 120000,
    previousOrderCount: 280,
    revenueChangePercent: 20.8,
    orderCountChangePercent: 11.4,
  );

  // A tall viewport. The dashboard is a scrolling list of six panels and
  // the default 800x600 test window builds only the first two, so an
  // assertion about the bottom panel would fail for a reason that has
  // nothing to do with the screen being wrong.
  void tall(WidgetTester tester) {
    tester.view.physicalSize = const Size(1000, 2600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  Widget host({
    SalesSummary? withSummary,
    Object? summaryError,
    List<SalesPoint>? series,
    List<TopProduct>? topProducts,
    Map<String, int>? breakdown,
    List<DeliveryTypeShare>? deliveryShares,
    int lowStock = 4,
    PresenceSnapshot? presence,
  }) {
    final failure = summaryError;
    return ProviderScope(
      overrides: [
        adminSalesSummaryProvider.overrideWith((ref) async {
          if (failure != null) throw failure;
          return withSummary ?? summary;
        }),
        adminSalesSeriesProvider.overrideWith((ref) async => series ?? const []),
        adminTopProductsProvider
            .overrideWith((ref) async => topProducts ?? const []),
        adminOrderStatusBreakdownProvider
            .overrideWith((ref) async => breakdown ?? const {}),
        adminLowStockCountProvider.overrideWith((ref) async => lowStock),
        // The night-shift panel. Overridden like every other leaf, and NOT
        // optional: left to the real provider it reaches for the network,
        // never resolves, and holds a spinner whose animation means
        // pumpAndSettle times out - a failure that names the dashboard while
        // meaning "a panel was added and the fixture did not hear about it".
        deliveryTypeSharesProvider
            .overrideWith((ref) async => deliveryShares ?? const []),
        // The live clock panel's shopper count. Overridden for exactly the
        // reason given above the night-shift panel - and this one arrived by
        // that route, breaking four tests here with "A Timer is still pending
        // even after the widget tree was disposed" the day the panel landed.
        adminPresenceProvider.overrideWith((ref) async =>
            presence ??
            const PresenceSnapshot(
                onlineNow: 12, windowSeconds: 300, available: true)),
      ],
      child: const MaterialApp(
        home: Scaffold(body: AdminDashboardScreen()),
      ),
    );
  }

  testWidgets('shows revenue in rupees with Indian grouping', (tester) async {
    tall(tester);
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    // 1,45,000 - not 145,000. See AdminFormat.
    expect(find.text('₹1,45,000'), findsOneWidget);
    expect(find.text('312'), findsOneWidget);
  });

  testWidgets('what the shop kept is shown beside what it sold', (tester) async {
    tall(tester);
    await tester.pumpWidget(host(
      withSummary: summary.copyWith(
        refunded: 5000,
        netRevenue: 140000,
        previousNetRevenue: 118000,
        netRevenueChangePercent: 18.6,
      ),
    ));
    await tester.pumpAndSettle();

    // Gross stays gross - the shop sold 1,45,000 - and the new card says
    // what survived the refunds.
    expect(find.text('₹1,45,000'), findsOneWidget);
    expect(find.text('₹1,40,000'), findsOneWidget);
    expect(find.text('Kept after refunds'), findsOneWidget);
  });

  testWidgets('a server that does not report it shows one card fewer, not zero',
      (tester) async {
    // THE FAILURE THIS PREVENTS. netRevenue defaulting to 0.0 like the
    // comparison fields would render "₹0" under "Kept after refunds" on the
    // screen a shopkeeper opens to see whether the week went well. An APK
    // can be newer than the backend it is talking to, so this is a real
    // state, not a hypothetical one.
    tall(tester);
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    expect(find.text('Kept after refunds'), findsNothing);
    expect(find.text('₹0'), findsNothing);
    expect(find.text('₹1,45,000'), findsOneWidget);
  });

  testWidgets('a failing sales query costs one panel, not the dashboard',
      (tester) async {
    tall(tester);
    await tester.pumpWidget(host(
      summaryError: Exception('backend down'),
      breakdown: const {'CONFIRMED': 3},
    ));
    await tester.pumpAndSettle();

    expect(find.text('Could not load sales figures.'), findsOneWidget);
    // The other panels still rendered. This is the whole point of loading
    // each one independently.
    expect(find.text('Orders by stage'), findsOneWidget);
    expect(find.text('Top products'), findsOneWidget);
  });

  testWidgets('an empty period says so instead of showing a blank card',
      (tester) async {
    tall(tester);
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    expect(find.text('No orders yet'), findsOneWidget);
    expect(find.text('Nothing sold in this period'), findsOneWidget);
  });

  testWidgets('top products show units and revenue', (tester) async {
    tall(tester);
    await tester.pumpWidget(host(topProducts: const [
      TopProduct(
        productId: 1,
        productName: 'Aashirvaad Atta 5kg',
        unitsSold: 42,
        revenue: 12600,
      ),
    ]));
    await tester.pumpAndSettle();

    expect(find.text('Aashirvaad Atta 5kg'), findsOneWidget);
    expect(find.text('#1  •  42 units'), findsOneWidget);
    expect(find.text('₹12,600'), findsOneWidget);
  });

  testWidgets('the period selector offers 7, 30 and 90 days', (tester) async {
    tall(tester);
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    expect(find.text('7 days'), findsOneWidget);
    expect(find.text('30 days'), findsOneWidget);
    expect(find.text('90 days'), findsOneWidget);
  });
  testWidgets('the clock panel shows the day, the date and the shopper count',
      (tester) async {
    tall(tester);
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    expect(find.text('Right now'), findsOneWidget);
    // Scoped to the panel: the dashboard is full of numbers, and a bare
    // find.text('12') would pass or fail on whatever the KPI cards happen to
    // show rather than on what this panel rendered.
    expect(
      find.descendant(
        of: find.byType(AdminLiveClockPanel),
        matching: find.text('12'),
      ),
      findsOneWidget,
    );
    expect(find.textContaining('shoppers active in the last 5 minutes'),
        findsOneWidget);
  });

  testWidgets('an unavailable count is never rendered as zero', (tester) async {
    // 0 tells a shopkeeper the shop is empty and they may go and do something
    // else; "--" tells them the dashboard cannot answer. Collapsing the
    // second into the first is the failure this pins.
    tall(tester);
    await tester.pumpWidget(host(
      presence: const PresenceSnapshot(
          onlineNow: null, windowSeconds: 300, available: false),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Count unavailable'), findsOneWidget);
    expect(
      find.descendant(
        of: find.byType(AdminLiveClockPanel),
        matching: find.text('0'),
      ),
      findsNothing,
    );
  });

  testWidgets('the clock cancels its timers when the dashboard goes away',
      (tester) async {
    // The panel starts three timers, one of them a sub-second alignment timer
    // that was originally fired and forgotten. Flutter's binding fails any
    // test torn down with a timer still pending, so this assertion IS the
    // check - it passes only if dispose() cancelled all of them.
    tall(tester);
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
    await tester.pumpAndSettle();
  });

}
