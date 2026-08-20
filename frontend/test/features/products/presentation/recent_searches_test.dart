import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/presentation/recent_searches.dart';

import '../../../core/notifications/fakes.dart';

void main() {
  group('RecentSearches', () {
    test('is empty before anything has been searched', () async {
      expect(await RecentSearches(storage: FakeKeyValueStore()).load(), isEmpty);
    });

    test('keeps the most recent search first', () async {
      final recent = RecentSearches(storage: FakeKeyValueStore());

      await recent.remember('sugar');
      await recent.remember('tata salt');

      expect(await recent.load(), ['tata salt', 'sugar']);
    });

    test('keeps a multi-word term as one entry', () async {
      // The whole reason entries are newline-separated: "tata salt" is one
      // search, not two.
      final recent = RecentSearches(storage: FakeKeyValueStore());

      await recent.remember('fortune oil');

      expect(await recent.load(), ['fortune oil']);
    });

    test('searching the same thing again moves it up rather than duplicating', () async {
      final recent = RecentSearches(storage: FakeKeyValueStore());

      await recent.remember('sugar');
      await recent.remember('milk');
      await recent.remember('sugar');

      expect(await recent.load(), ['sugar', 'milk']);
    });

    test('de-duplicates case-insensitively but keeps what the customer typed', () async {
      final recent = RecentSearches(storage: FakeKeyValueStore());

      await recent.remember('sugar');
      await recent.remember('Sugar');

      expect(await recent.load(), ['Sugar']);
    });

    test('stays bounded', () async {
      final recent = RecentSearches(storage: FakeKeyValueStore());

      for (var i = 1; i <= 12; i++) {
        await recent.remember('term $i');
      }

      final loaded = await recent.load();
      expect(loaded, hasLength(6));
      expect(loaded.first, 'term 12');
    });

    test('survives a restart', () async {
      final storage = FakeKeyValueStore();
      await RecentSearches(storage: storage).remember('haldi');

      expect(await RecentSearches(storage: storage).load(), ['haldi']);
    });

    test('ignores blank terms', () async {
      final recent = RecentSearches(storage: FakeKeyValueStore());

      await recent.remember('   ');

      expect(await recent.load(), isEmpty);
    });

    test('clearing removes everything', () async {
      final recent = RecentSearches(storage: FakeKeyValueStore());
      await recent.remember('sugar');

      await recent.clear();

      expect(await recent.load(), isEmpty);
    });

    test('a storage failure degrades to no history rather than breaking search', () async {
      final recent = RecentSearches(storage: FakeKeyValueStore(failReads: true));

      expect(await recent.load(), isEmpty);
    });

    test('a failed save does not throw at the search screen', () async {
      final recent = RecentSearches(storage: FakeKeyValueStore(failWrites: true));

      await expectLater(recent.remember('sugar'), completes);
    });
  });
}
