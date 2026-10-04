import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/assistant/presentation/shopping_assistant_basket_tile.dart';

void main() {
  testWidgets('long offer text keeps useful width and action below it',
      (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    Map<String, dynamic>? reviewed;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              ShoppingAssistantBasketTile(
                data: {
                  'requestedItem': 'Tata namak',
                  'offer': {
                    'name':
                        'Tata Salt Iodised 1 kg GP Store nearby Buy Online offer',
                    'shopName': 'A nearby grocery store',
                    'commerceLabel': 'Buy Online',
                    'distanceKm': 1.2,
                    'addable': false,
                  },
                },
                onReview: (offer) async => reviewed = offer,
                onAdd: (_) async {},
              ),
            ],
          ),
        ),
      ),
    );

    final nameFinder =
        find.byKey(const ValueKey<String>('shopping-assistant-offer-name'));
    final name = tester.widget<Text>(nameFinder);
    expect(name.maxLines, 2);
    expect(tester.getSize(nameFinder).width, greaterThan(250));
    expect(find.text('View options'), findsOneWidget);
    expect(find.text('1.2 km'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(nameFinder);
    await tester.pump();
    expect(reviewed?['name'], contains('Tata Salt Iodised'));
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('View options'));
    await tester.pump();
    expect(reviewed?['name'], contains('Tata Salt Iodised'));
  });

  testWidgets('sub-kilometre distance is rounded to whole metres', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ShoppingAssistantBasketTile(
            data: {
              'requestedItem': 'masala',
              'offer': {
                'name': 'Biryani Masala',
                'distanceKm': 0.015305327818,
                'addable': false,
              },
            },
            onReview: (_) async {},
            onAdd: (_) async {},
          ),
        ),
      ),
    );

    expect(find.text('15 m'), findsOneWidget);
    expect(find.textContaining('0.015305'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('addable suggestions retain the explicit review-and-add action',
      (tester) async {
    Map<String, dynamic>? added;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ShoppingAssistantBasketTile(
            data: {
              'offer': {'name': 'Rice', 'addable': true},
            },
            onReview: (_) async {},
            onAdd: (offer) async => added = offer,
          ),
        ),
      ),
    );

    await tester.tap(find.text('Review & add'));
    await tester.pump();
    expect(added?['name'], 'Rice');
  });
}
