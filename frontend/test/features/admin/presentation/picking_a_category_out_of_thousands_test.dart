import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/admin_products_repository.dart';
import 'package:gpstore/features/admin/domain/catalogue_item.dart';
import 'package:gpstore/features/admin/presentation/admin_providers.dart';
import 'package:gpstore/features/admin/presentation/category_picker_sheet.dart';

import '../../../support/test_api_client.dart';

/// Choosing a category when the catalogue has thousands of them.
///
/// <h2>Why this test exists</h2>
///
/// An APK audit of a build that predated the picker reported "I searched the
/// binary for a category-search string and did not find one", and concluded
/// the feature was unproven. That was the right conclusion from the evidence
/// available: a compiled Flutter binary is a poor place to look for a widget
/// tree. This is the evidence that belongs in the repository instead - it
/// drives the real sheet against the real repository and asserts what a
/// merchant can actually do.
///
/// It also guards the specific thing that was wrong AFTER the picker
/// shipped: the child count beside a parent was decoration. It said "12"
/// next to Electronics and tapping the row selected Electronics, so those
/// twelve categories were unreachable unless the merchant already knew their
/// names well enough to type them. The endpoint to list them existed and
/// nothing called it.
void main() {
  setUpAll(setUpFakeSecureStorage);

  Map<String, dynamic> category(
    int id,
    String name, {
    bool mine = false,
    int children = 0,
    String? parentName,
  }) =>
      {
        'id': id,
        'name': name,
        'parentId': parentName == null ? null : 900,
        'parentName': parentName,
        'imageUrl': null,
        'usedByThisShop': mine,
        'childCount': children,
      };

  /// A catalogue shaped like the real one: the shop's own few, and a long
  /// tail belonging to everybody else.
  final root = [
    category(1, 'Atta, Rice & Dal', mine: true),
    category(2, 'Beverages', mine: true),
    category(10, 'Electronics', children: 3),
    category(11, 'Baby Care'),
    category(12, 'Biscuits & Bakery'),
  ];

  final electronicsChildren = [
    category(101, 'Mobile Phones', parentName: 'Electronics', children: 2),
    category(102, 'Mobile Accessories', parentName: 'Electronics'),
    category(103, 'Home Audio', parentName: 'Electronics'),
  ];

  final phoneChildren = [
    category(201, 'Smartphones', parentName: 'Mobile Phones'),
    category(202, 'Feature Phones', parentName: 'Mobile Phones'),
  ];

  ({Widget widget, List<Map<String, dynamic>> searches, List<String> paths}) host() {
    final searches = <Map<String, dynamic>>[];
    final paths = <String>[];
    final adapter = FakeHttpClientAdapter();

    adapter.on('GET', '/api/shop/category-search', (options) {
      searches.add(Map<String, dynamic>.from(options.queryParameters));
      paths.add(options.path);
      final q = (options.queryParameters['q'] as String?)?.trim().toLowerCase();
      if (q == null || q.isEmpty) return FakeResponse(root);
      // The SERVER does the matching. This mirrors that: the sheet must not
      // be filtering a downloaded list on the phone.
      return FakeResponse(
          root.where((c) => (c['name'] as String).toLowerCase().contains(q)).toList());
    });

    for (final entry in {10: electronicsChildren, 101: phoneChildren}.entries) {
      adapter.on('GET', '/api/shop/category-search/${entry.key}/children', (options) {
        paths.add(options.path);
        return FakeResponse(entry.value);
      });
    }

    return (
      searches: searches,
      paths: paths,
      widget: ProviderScope(
        overrides: [
          adminProductsRepositoryProvider.overrideWithValue(
              AdminProductsRepository(apiClient: buildTestApiClient(adapter))),
        ],
        child: const MaterialApp(home: Scaffold(body: CategoryPickerSheet())),
      ),
    );
  }

  void tall(WidgetTester tester) {
    tester.view.physicalSize = const Size(1100, 2600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  group('Search', () {
    testWidgets('the picker opens with a search field, not a bare list',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      // The exact string an APK audit went looking for.
      expect(find.text('Search categories…'), findsOneWidget);
      expect(find.byType(TextField), findsOneWidget);
    });

    testWidgets("puts the shop's own categories first, under their own heading",
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      expect(find.text('YOUR CATEGORIES'), findsOneWidget);
      expect(find.text('ALL CATEGORIES'), findsOneWidget);

      final own = tester.getTopLeft(find.text('Atta, Rice & Dal')).dy;
      final other = tester.getTopLeft(find.text('Baby Care')).dy;
      expect(own, lessThan(other),
          reason: "a merchant's own departments come before everybody else's");
    });

    testWidgets('sends the query to the server rather than filtering on the phone',
        (tester) async {
      tall(tester);
      final h = host();
      await tester.pumpWidget(h.widget);
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'electr');
      await tester.pumpAndSettle(const Duration(seconds: 2));

      expect(h.searches.last['q'], 'electr',
          reason: 'the server does the matching over the whole catalogue');
      expect(find.text('Electronics'), findsOneWidget);
      expect(find.text('Baby Care'), findsNothing);
    });

    testWidgets('says so when nothing matched, and suggests what to do',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'zzzzz');
      await tester.pumpAndSettle(const Duration(seconds: 2));

      expect(find.text('No category matched'), findsOneWidget);
      expect(find.textContaining('shorter word'), findsOneWidget);
    });
  });

  group('Walking the tree', () {
    /// THE BUG THIS EXISTS FOR. The count was drawn and nothing happened when
    /// you pressed it.
    testWidgets('the child count opens the children', (tester) async {
      tall(tester);
      final h = host();
      await tester.pumpWidget(h.widget);
      await tester.pumpAndSettle();

      expect(find.text('Mobile Phones'), findsNothing);

      await tester.tap(find.text('3'));
      await tester.pumpAndSettle();

      expect(h.paths, contains('/api/shop/category-search/10/children'));
      expect(find.text('Mobile Phones'), findsOneWidget);
      expect(find.text('Mobile Accessories'), findsOneWidget);
      expect(find.text('INSIDE ELECTRONICS'), findsOneWidget);
    });

    testWidgets('a parent is still choosable once you are inside it',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();
      await tester.tap(find.text('3'));
      await tester.pumpAndSettle();

      expect(find.text('Use Electronics itself'), findsOneWidget);
    });

    testWidgets('goes more than one level deep, and back up again',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      await tester.tap(find.text('3'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('2'));
      await tester.pumpAndSettle();

      expect(find.text('Smartphones'), findsOneWidget);
      expect(find.text('All categories  ›  Electronics  ›  Mobile Phones'),
          findsOneWidget);

      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();
      expect(find.text('Mobile Accessories'), findsOneWidget,
          reason: 'back goes up one level, not out of the sheet');

      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();
      expect(find.text('YOUR CATEGORIES'), findsOneWidget,
          reason: 'and eventually back to the root');
    });

    testWidgets('typing leaves the tree and searches everything',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();
      await tester.tap(find.text('3'));
      await tester.pumpAndSettle();
      expect(find.text('Mobile Accessories'), findsOneWidget);

      // "atta" is not under Electronics. A merchant who types it means the
      // whole catalogue, and searching only the current branch would find
      // nothing and read as a broken search.
      await tester.enterText(find.byType(TextField), 'atta');
      await tester.pumpAndSettle(const Duration(seconds: 2));

      expect(find.text('Atta, Rice & Dal'), findsOneWidget);
      expect(find.textContaining('›'), findsNothing,
          reason: 'the breadcrumb is gone because we left the tree');
    });
  });

  group('Choosing', () {
    testWidgets('returns the category that was tapped', (tester) async {
      tall(tester);
      CategoryOption? chosen;
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/shop/category-search', (_) => FakeResponse(root));

      await tester.pumpWidget(ProviderScope(
        overrides: [
          adminProductsRepositoryProvider.overrideWithValue(
              AdminProductsRepository(apiClient: buildTestApiClient(adapter))),
        ],
        child: MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () async {
                  chosen = await CategoryPickerSheet.show(context);
                },
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ));

      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Baby Care'));
      await tester.pumpAndSettle();

      expect(chosen?.id, 11);
      expect(chosen?.name, 'Baby Care');
    });
  });
}
