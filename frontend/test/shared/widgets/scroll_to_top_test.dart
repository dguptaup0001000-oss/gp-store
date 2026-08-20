import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/shared/widgets/scroll_to_top.dart';

/// The target opacity of the pill. There is exactly one AnimatedOpacity in
/// these trees - the button's own - so this is unambiguous.
double pillOpacity(WidgetTester tester) =>
    tester.widget<AnimatedOpacity>(find.byType(AnimatedOpacity)).opacity;

/// Whether the pill is currently hit-testable.
///
/// Has to be pinned down from both sides. Scrollable builds its own
/// IgnorePointer (it stops taking pointers mid-fling), so "the IgnorePointer
/// inside ScrollToTop" matches two widgets; and Navigator has one too, so
/// "an ancestor of the pill" reaches past this widget entirely. The
/// intersection - inside ScrollToTop AND above the pill's own label - is
/// exactly one widget, and tester.widget throws if that ever stops being
/// true rather than quietly inspecting the wrong one.
bool pillAcceptsTaps(WidgetTester tester) => !tester
    .widget<IgnorePointer>(
      find.ancestor(
        of: find.text('Back to top'),
        matching: find.descendant(of: find.byType(ScrollToTop), matching: find.byType(IgnorePointer)),
      ),
    )
    .ignoring;

void main() {
  late ScrollController controller;
  late int pageBuilds;

  Widget harness({int items = 200}) {
    pageBuilds = 0;
    return MaterialApp(
      home: Scaffold(
        body: ScrollToTop(
          builder: (context, scrollController) {
            pageBuilds++;
            controller = scrollController;
            return ListView.builder(
              controller: scrollController,
              itemCount: items,
              itemBuilder: (context, index) => SizedBox(height: 60, child: Text('item $index')),
            );
          },
        ),
      ),
    );
  }

  group('ScrollToTop', () {
    testWidgets('is hidden at the top of the page', (tester) async {
      await tester.pumpWidget(harness());
      await tester.pumpAndSettle();

      expect(pillOpacity(tester), 0);
      expect(pillAcceptsTaps(tester), isFalse);
    });

    testWidgets('stays hidden after a small scroll', (tester) async {
      // "Do not show it immediately after a tiny scroll" - a flick of a
      // couple of rows is not a customer who wants to get back to the top.
      await tester.pumpWidget(harness());
      await tester.pumpAndSettle();

      controller.jumpTo(120);
      await tester.pumpAndSettle();

      expect(pillOpacity(tester), 0);
    });

    testWidgets('appears once the customer has scrolled a real distance', (tester) async {
      await tester.pumpWidget(harness());
      await tester.pumpAndSettle();

      controller.jumpTo(3000);
      await tester.pumpAndSettle();

      expect(pillOpacity(tester), 1);
      expect(pillAcceptsTaps(tester), isTrue);
    });

    testWidgets('tapping it returns the page to the very top', (tester) async {
      await tester.pumpWidget(harness());
      await tester.pumpAndSettle();

      controller.jumpTo(3000);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Back to top'));
      await tester.pumpAndSettle();

      expect(controller.offset, 0);
    });

    testWidgets('hides itself again once back at the top', (tester) async {
      await tester.pumpWidget(harness());
      await tester.pumpAndSettle();

      controller.jumpTo(3000);
      await tester.pumpAndSettle();
      expect(pillOpacity(tester), 1);

      await tester.tap(find.text('Back to top'));
      await tester.pumpAndSettle();

      expect(pillOpacity(tester), 0);
      expect(pillAcceptsTaps(tester), isFalse);
    });

    testWidgets('does not swallow taps meant for the page beneath it', (tester) async {
      // A fully transparent button still takes hit tests. Without the
      // IgnorePointer this would eat taps aimed at an ADD button sitting
      // where the invisible pill floats.
      var tapsOnPage = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ScrollToTop(
              builder: (context, scrollController) => ListView(
                controller: scrollController,
                children: [
                  SizedBox(
                    height: 2000,
                    child: GestureDetector(
                      onTap: () => tapsOnPage++,
                      child: const ColoredBox(color: Color(0xFFEEEEEE)),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Where the hidden pill sits: horizontally centred, near the bottom.
      final size = tester.getSize(find.byType(Scaffold));
      await tester.tapAt(Offset(size.width / 2, size.height - 34));
      await tester.pumpAndSettle();

      expect(tapsOnPage, 1);
    });

    testWidgets('scrolling does not rebuild the page', (tester) async {
      // The performance claim, asserted rather than assumed: the scroll
      // listener writes to a ValueNotifier that only the pill subscribes to,
      // so the product grid above must not rebuild on scroll - not even when
      // the button appears and disappears.
      await tester.pumpWidget(harness());
      await tester.pumpAndSettle();

      final buildsAfterFirstFrame = pageBuilds;

      for (final offset in [200.0, 1500.0, 3000.0, 4500.0, 100.0, 3000.0]) {
        controller.jumpTo(offset);
        await tester.pumpAndSettle();
      }

      expect(pageBuilds, buildsAfterFirstFrame);
    });

    testWidgets('a short page never shows the button', (tester) async {
      // Nothing to go back to.
      await tester.pumpWidget(harness(items: 3));
      await tester.pumpAndSettle();

      expect(pillOpacity(tester), 0);
    });

    testWidgets('disposes cleanly when the page is popped', (tester) async {
      // Guards the leak the controller and notifier would otherwise be.
      await tester.pumpWidget(harness());
      await tester.pumpAndSettle();
      controller.jumpTo(3000);
      await tester.pumpAndSettle();

      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: SizedBox())));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    });
  });
}
