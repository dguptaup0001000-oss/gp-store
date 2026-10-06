import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/images/local_catalogue_artwork.dart';

void main() {
  Widget host(Widget child) => MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(width: 140, height: 140, child: child),
          ),
        ),
      );

  testWidgets('renders semantic product artwork without a network image',
      (tester) async {
    await tester.pumpWidget(host(const LocalCatalogueArtwork(
      seed: 'Tata Salt 1 kg',
      role: LocalArtworkRole.product,
      variant: 10,
    )));

    expect(find.text('🧂'), findsOneWidget);
    expect(find.byIcon(Icons.restaurant_rounded), findsOneWidget);
    expect(find.byType(Image), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('service role has a service affordance even for unknown names',
      (tester) async {
    await tester.pumpWidget(host(const LocalCatalogueArtwork(
      seed: 'Premium Package TEST 101',
      role: LocalArtworkRole.serviceAtShop,
      variant: 101,
    )));

    expect(find.text('🛠️'), findsOneWidget);
    expect(find.byIcon(Icons.build_circle_outlined), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('category artwork is compact and deterministic', (tester) async {
    const artwork = LocalCatalogueArtwork(
      seed: 'Clothing and Fashion',
      role: LocalArtworkRole.category,
      variant: 7,
    );

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SizedBox(width: 56, height: 56, child: artwork),
        ),
      ),
    );

    expect(find.text('👕'), findsOneWidget);
    expect(find.byIcon(Icons.checkroom_rounded), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
