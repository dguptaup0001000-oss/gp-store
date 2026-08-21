import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/search/search_debouncer.dart';

void main() {
  late SearchDebouncer debouncer;
  late List<String> searched;
  late List<CancelToken> tokens;
  late int cleared;

  setUp(() {
    debouncer = SearchDebouncer(delay: const Duration(milliseconds: 50));
    searched = [];
    tokens = [];
    cleared = 0;
  });

  tearDown(() => debouncer.dispose());

  void type(String value) {
    debouncer.onQueryChanged(
      value,
      onSearch: (query, token) {
        searched.add(query);
        tokens.add(token);
      },
      onCleared: () => cleared++,
    );
  }

  group('how many requests a typing burst produces', () {
    testWidgets('a burst becomes one request, not one per keystroke', (tester) async {
      // The whole point: "s, su, sug, suga, sugar" must not be five searches.
      for (final fragment in ['s', 'su', 'sug', 'suga', 'sugar']) {
        type(fragment);
        await tester.pump(const Duration(milliseconds: 10));
      }
      await tester.pump(const Duration(milliseconds: 100));

      expect(searched, ['sugar']);
    });

    testWidgets('a pause mid-word does produce a search, then another', (tester) async {
      type('sug');
      await tester.pump(const Duration(milliseconds: 100));
      type('sugar');
      await tester.pump(const Duration(milliseconds: 100));

      expect(searched, ['sug', 'sugar']);
    });
  });

  group('what never reaches the network', () {
    testWidgets('a single character does not search', (tester) async {
      // One character matches a large slice of any catalogue - the server
      // does real work to return something useless.
      type('s');
      await tester.pump(const Duration(milliseconds: 100));

      expect(searched, isEmpty);
    });

    testWidgets('a too-short query does not wipe existing results', (tester) async {
      // The customer is mid-word. Clearing what they can see on the way to a
      // longer query is worse than leaving it.
      type('s');
      await tester.pump(const Duration(milliseconds: 100));

      expect(cleared, 0);
    });

    testWidgets('an empty box clears immediately, without a search', (tester) async {
      type('sugar');
      await tester.pump(const Duration(milliseconds: 100));
      searched.clear();

      type('');
      // No pump: clearing should not wait for a debounce.
      expect(cleared, 1);
      await tester.pump(const Duration(milliseconds: 100));
      expect(searched, isEmpty);
    });

    testWidgets('whitespace only counts as empty', (tester) async {
      type('   ');
      expect(cleared, 1);
      await tester.pump(const Duration(milliseconds: 100));
      expect(searched, isEmpty);
    });

    testWidgets('the query is trimmed before it is searched', (tester) async {
      type('  sugar  ');
      await tester.pump(const Duration(milliseconds: 100));

      expect(searched, ['sugar']);
    });
  });

  group('cancellation', () {
    testWidgets('a superseded search is cancelled, not merely ignored', (tester) async {
      // Debouncing limits how often a request STARTS; it does nothing about
      // one already in flight. Without cancelling, the backend still pays for
      // every keystroke that got overtaken.
      type('sugar');
      await tester.pump(const Duration(milliseconds: 100));
      expect(tokens, hasLength(1));
      expect(tokens.first.isCancelled, isFalse);

      type('sugar free');
      await tester.pump(const Duration(milliseconds: 100));

      expect(tokens.first.isCancelled, isTrue, reason: 'the first search should have been aborted');
      expect(tokens.last.isCancelled, isFalse);
    });

    testWidgets('typing again during the debounce cancels the previous request', (tester) async {
      type('sugar');
      await tester.pump(const Duration(milliseconds: 100));
      final first = tokens.first;

      // Still within the next debounce window.
      type('sug');
      expect(first.isCancelled, isTrue);

      // The assertion above is the test. This pump is bookkeeping: typing
      // scheduled a live 50ms Timer, and testWidgets asserts !timersPending
      // when the body returns - BEFORE tearDown gets to call dispose() - so
      // leaving it armed fails the test for a reason that has nothing to do
      // with what is being checked.
      await tester.pump(const Duration(milliseconds: 100));
    });

    testWidgets('dispose cancels both the pending timer and the live request', (tester) async {
      // Otherwise a search fired after the screen is gone resolves into a
      // disposed widget.
      type('sugar');
      await tester.pump(const Duration(milliseconds: 100));
      final live = tokens.first;

      type('sugar cane');
      debouncer.dispose();
      await tester.pump(const Duration(milliseconds: 200));

      expect(live.isCancelled, isTrue);
      expect(searched, ['sugar'], reason: 'the pending search must not fire after dispose');
    });

    testWidgets('nothing fires after dispose', (tester) async {
      debouncer.dispose();
      type('sugar');
      await tester.pump(const Duration(milliseconds: 200));

      expect(searched, isEmpty);
      expect(cleared, 0);
    });
  });

  group('searchNow', () {
    testWidgets('runs immediately, with no debounce', (tester) async {
      // A tapped suggestion or a submitted query - the customer already chose.
      debouncer.searchNow('sugar', onSearch: (q, t) {
        searched.add(q);
        tokens.add(t);
      });

      expect(searched, ['sugar']);
    });

    testWidgets('cancels a pending debounced search', (tester) async {
      type('sug');
      debouncer.searchNow('sugar', onSearch: (q, t) => searched.add(q));
      await tester.pump(const Duration(milliseconds: 200));

      expect(searched, ['sugar'], reason: 'the debounced "sug" must not also fire');
    });

    testWidgets('ignores an empty term', (tester) async {
      debouncer.searchNow('   ', onSearch: (q, t) => searched.add(q));
      expect(searched, isEmpty);
    });
  });

  test('the default interval is inside the 250-300ms band', () {
    // Below ~200ms a fast typist still generates several requests; above
    // ~400ms the wait starts reading as lag.
    final defaults = SearchDebouncer();
    expect(defaults.delay.inMilliseconds, greaterThanOrEqualTo(250));
    expect(defaults.delay.inMilliseconds, lessThanOrEqualTo(300));
    expect(defaults.minLength, greaterThanOrEqualTo(2));
    defaults.dispose();
  });
}
