import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/images/gp_network_image.dart';

/// The safety contract, which is the whole reason this widget exists.
///
/// A product photo is data the shop does not control: an admin can leave the
/// field blank, paste a typo, or point at a host that is down that morning.
/// Every one of those has to end at the same quiet placeholder, because the
/// alternative is a customer looking at a grey rectangle - or worse, an
/// exception - where a packet of atta should be.
Widget _host(Widget child, {Size size = const Size(160, 160)}) {
  return MaterialApp(
    home: Scaffold(
      body: Center(
        child: SizedBox(width: size.width, height: size.height, child: child),
      ),
    ),
  );
}

void main() {
  group('GpNetworkImage', () {
    testWidgets('a null url draws the placeholder rather than nothing', (tester) async {
      await tester.pumpWidget(_host(
        const GpNetworkImage(url: null, renderWidth: 160),
      ));

      expect(tester.takeException(), isNull);
      expect(find.byType(Icon), findsOneWidget);
    });

    testWidgets('an empty url is treated exactly like a null one', (tester) async {
      await tester.pumpWidget(_host(
        const GpNetworkImage(url: '', renderWidth: 160),
      ));

      expect(tester.takeException(), isNull);
      expect(find.byType(Icon), findsOneWidget);
    });

    testWidgets('a caller can name the icon that stands in for a missing image', (tester) async {
      await tester.pumpWidget(_host(
        const GpNetworkImage(
          url: null,
          renderWidth: 160,
          fallbackIcon: Icons.shopping_basket_outlined,
        ),
      ));

      final icon = tester.widget<Icon>(find.byType(Icon));
      expect(icon.icon, Icons.shopping_basket_outlined);
    });

    testWidgets('the placeholder icon is scaled to its box, not fixed', (tester) async {
      await tester.pumpWidget(_host(
        const GpNetworkImage(url: null, renderWidth: 40),
        size: const Size(40, 40),
      ));
      final small = tester.widget<Icon>(find.byType(Icon)).size!;

      await tester.pumpWidget(_host(
        const GpNetworkImage(url: null, renderWidth: 160),
      ));
      final large = tester.widget<Icon>(find.byType(Icon)).size!;

      expect(small, lessThan(large),
          reason: 'a 40px thumbnail and a 160px card must not get the same glyph');
    });

    testWidgets('the placeholder icon never outgrows a large box', (tester) async {
      await tester.pumpWidget(_host(
        const GpNetworkImage(url: null, renderWidth: 1200),
        size: const Size(1200, 1200),
      ));

      final icon = tester.widget<Icon>(find.byType(Icon));
      expect(icon.size, lessThanOrEqualTo(44.0),
          reason: 'past a point a "missing image" glyph reads as an error, not a stand-in');
    });

    testWidgets('fill sizes itself from its box instead of a hardcoded width', (tester) async {
      // The point of the fill constructor: no number at the call site, so
      // nothing to get wrong on a screen size nobody tested on.
      await tester.pumpWidget(_host(
        const GpNetworkImage.fill(url: null),
        size: const Size(200, 200),
      ));

      expect(tester.takeException(), isNull);
      expect(find.byType(Icon), findsOneWidget);
    });

    testWidgets('fill survives an unbounded width', (tester) async {
      // A Row gives its children infinite width. Measuring would produce
      // Infinity, and Infinity * pixelRatio is a decode width that throws.
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: const Row(
            children: [
              SizedBox(height: 100, child: GpNetworkImage.fill(url: null)),
            ],
          ),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('the placeholder takes the same corners as the image would', (tester) async {
      final radius = BorderRadius.circular(8);
      await tester.pumpWidget(_host(
        GpNetworkImage(url: null, renderWidth: 160, borderRadius: radius),
      ));

      // A square placeholder inside a rounded card is a visible corner of the
      // wrong shape - the fallback has to be the same silhouette as the thing
      // it stands in for.
      final decorated = tester.widgetList<Container>(find.byType(Container));
      final decoration = decorated
          .map((c) => c.decoration)
          .whereType<BoxDecoration>()
          .firstWhere((d) => d.borderRadius != null);
      expect(decoration.borderRadius, radius);
    });
  });
}
