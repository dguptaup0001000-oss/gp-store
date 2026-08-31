import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/dashboard/admin_dashboard_screen.dart';
import 'package:gpstore/features/admin/domain/analytics_models.dart';
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
}
