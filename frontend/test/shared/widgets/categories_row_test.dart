import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/util/app_haptics.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/shared/widgets/categories_row.dart';

/// The category shelf.
///
/// Two rows in a horizontal scroller is a layout with one obvious way to go
/// wrong - dropping the last aisle when the count is odd - and one subtle one:
/// growing taller as the catalogue grows, which would push everything below
/// it off the home screen.
List<Category> _categories(int count) => [
      for (var i = 1; i <= count; i++) Category(id: i, name: 'Aisle $i'),
    ];

Widget _host(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  // Counted, not fired: a widget test has no vibration motor, and the tap
  // assertions below are about navigation rather than haptics.
  setUp(() {
    AppHaptics.resetForTest();
    AppHaptics.enabled = false;
  });

  group('CategoriesRow', () {
    testWidgets('an odd number of categories still shows the last one', (tester) async {
      await tester.pumpWidget(_host(CategoriesRow(categories: _categories(7))));

      // Every aisle reachable by scrolling; the seventh is the one a
      // floor-division column count would silently drop.
      expect(find.text('Aisle 7', skipOffstage: false), findsOneWidget);
    });

    testWidgets('shows every category, not just the first screenful', (tester) async {
      await tester.pumpWidget(_host(CategoriesRow(categories: _categories(12))));

      for (var i = 1; i <= 12; i++) {
        expect(find.text('Aisle $i', skipOffstage: false), findsOneWidget);
      }
    });

    testWidgets('height does not grow with the catalogue', (tester) async {
      await tester.pumpWidget(_host(CategoriesRow(categories: _categories(4))));
      final short = tester.getSize(find.byType(CategoriesRow)).height;

      await tester.pumpWidget(_host(CategoriesRow(categories: _categories(40))));
      final long = tester.getSize(find.byType(CategoriesRow)).height;

      expect(long, short,
          reason: 'the shelf scrolls sideways - ten times the aisles must cost '
              'the same vertical space, or the sections below fall off the page');
    });

    testWidgets('an empty catalogue takes no space at all', (tester) async {
      await tester.pumpWidget(_host(CategoriesRow(categories: const [])));

      expect(tester.getSize(find.byType(CategoriesRow)), Size.zero,
          reason: 'a titled shelf with nothing on it is worse than no shelf');
    });

    testWidgets('tapping a card reports that category', (tester) async {
      Category? tapped;
      await tester.pumpWidget(_host(CategoriesRow(
        categories: _categories(4),
        onCategoryTap: (category) => tapped = category,
      )));

      await tester.tap(find.text('Aisle 3'));
      await tester.pump();

      expect(tapped?.id, 3);
    });

    testWidgets('no card is tappable when the caller supplied no handler', (tester) async {
      await tester.pumpWidget(_host(CategoriesRow(categories: _categories(4))));

      await tester.tap(find.text('Aisle 1'));
      await tester.pump();

      expect(tester.takeException(), isNull);
    });
  });
}
