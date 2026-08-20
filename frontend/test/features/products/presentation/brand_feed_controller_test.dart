import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/brand_models.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/brand_feed_controller.dart';

/// A fake catalogue: brands in a fixed order, each with a fixed number of
/// products, paged the way the real endpoint pages them.
class FakeCatalogue {
  FakeCatalogue(this.productsPerBrand, {this.pageSize = 20});

  /// Brand name -> how many products it has. Insertion order is the
  /// catalogue order, matching the backend's `order by brand asc`.
  final Map<String, int> productsPerBrand;
  final int pageSize;

  /// Brands that have products but do NOT appear in the brand list - the
  /// real case being a brand whose last product just went inactive, or a
  /// cached brand list that is a moment stale.
  final Map<String, int> offCatalogue = {};

  /// Every (brand, page) actually requested, in order. The duplicate-request
  /// rules are checked against this.
  final List<String> requests = [];

  int brandListCalls = 0;

  /// Set to make the next fetch fail once.
  bool failNextFetch = false;

  /// When set, fetches wait on this until it is completed - lets a test hold
  /// a request open and fire scroll events underneath it.
  Completer<void>? gate;

  int _nextId = 1;
  final Map<String, List<Product>> _catalogue = {};

  List<Product> _productsFor(String brand) {
    return _catalogue.putIfAbsent(brand, () {
      final count = productsPerBrand[brand] ?? offCatalogue[brand] ?? 0;
      return List.generate(count, (_) {
        final id = _nextId++;
        return Product(id: id, name: '$brand item $id', variants: const []);
      });
    });
  }

  Future<List<BrandSummary>> loadBrands() async {
    brandListCalls++;
    return productsPerBrand.entries
        .map((e) => BrandSummary(brand: e.key, productCount: e.value))
        .toList();
  }

  Future<({List<Product> products, int totalElements, int totalPages})> fetchPage({
    required String brand,
    BrandSortOption? sort,
    bool inStockOnly = false,
    String? keyword,
    required int page,
  }) async {
    requests.add('$brand#$page');

    if (gate != null) await gate!.future;

    if (failNextFetch) {
      failNextFetch = false;
      throw StateError('network down');
    }

    var all = _productsFor(brand);
    // The real endpoint filters server-side; the fake only needs to be
    // consistent, not clever.
    if (keyword != null && keyword.isNotEmpty) {
      all = all.where((p) => p.name.contains(keyword)).toList();
    }

    final start = page * pageSize;
    final end = (start + pageSize).clamp(0, all.length);
    final slice = start >= all.length ? <Product>[] : all.sublist(start, end);

    return (
      products: slice,
      totalElements: all.length,
      totalPages: all.isEmpty ? 0 : (all.length / pageSize).ceil(),
    );
  }
}

BrandFeedController controllerFor(
  FakeCatalogue catalogue, {
  required String anchor,
}) {
  return BrandFeedController(
    anchorBrand: BrandSummary(brand: anchor, productCount: catalogue.productsPerBrand[anchor] ?? 0),
    loadBrands: catalogue.loadBrands,
    fetchPage: catalogue.fetchPage,
  );
}

/// Scrolls until the feed says it has ended, with a hard cap so a bug that
/// never terminates fails the test instead of hanging it.
Future<void> scrollToEnd(BrandFeedController feed, {int maxSteps = 200}) async {
  for (var i = 0; i < maxSteps && !feed.reachedEnd; i++) {
    await feed.advance();
  }
}

void main() {
  group('BrandFeedController', () {
    test('starts on the brand the customer opened', () async {
      final catalogue = FakeCatalogue({'Harpic': 5, 'Lizol': 5, 'Vim': 5});
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();

      expect(feed.sections.first.brand.brand, 'Lizol');
      expect(feed.sections.first.products, hasLength(5));
    });

    test('rolls into the next brand instead of ending', () async {
      // THE HEADLINE RULE: one brand running out is not the end of the feed.
      final catalogue = FakeCatalogue({'Harpic': 3, 'Lizol': 3, 'Vim': 3});
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();
      expect(feed.reachedEnd, isFalse, reason: 'Lizol ran out, but three brands remain');

      await feed.advance();

      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
      expect(feed.reachedEnd, isFalse);
    });

    test('keeps everything already loaded when the next brand is appended', () async {
      final catalogue = FakeCatalogue({'Harpic': 3, 'Lizol': 3});
      final feed = controllerFor(catalogue, anchor: 'Harpic');

      await feed.start();
      final firstBrandProducts = feed.sections.first.products.map((p) => p.id).toList();

      await feed.advance();

      // Appended below, never replacing - which is what keeps the scroll
      // position where the customer left it.
      expect(feed.sections.first.products.map((p) => p.id).toList(), firstBrandProducts);
      expect(feed.sections, hasLength(2));
    });

    test('pages within a brand before moving to the next one', () async {
      final catalogue = FakeCatalogue({'Lizol': 45, 'Vim': 5}, pageSize: 20);
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();
      expect(feed.sections.single.products, hasLength(20));

      await feed.advance();
      expect(feed.sections, hasLength(1), reason: 'Lizol still has pages left');
      expect(feed.sections.single.products, hasLength(40));

      await feed.advance();
      expect(feed.sections.single.products, hasLength(45));

      await feed.advance();
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
    });

    test('one scroll loads one page, not the whole brand', () async {
      // Paging exists so a customer who flicks once does not pull a
      // 45-product brand down the wire in a single request storm.
      final catalogue = FakeCatalogue({'Lizol': 200, 'Vim': 5}, pageSize: 20);
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();
      catalogue.requests.clear();

      await feed.advance();

      expect(catalogue.requests, ['Lizol#1']);
      expect(feed.sections.single.products, hasLength(40));
    });

    test('wraps past the end of the catalogue so every brand is reachable', () async {
      // Opening the alphabetically last brand must not end the feed at once.
      final catalogue = FakeCatalogue({'Aashirvaad': 2, 'Lizol': 2, 'Vim': 2});
      final feed = controllerFor(catalogue, anchor: 'Vim');

      await feed.start();
      await scrollToEnd(feed);

      expect(feed.sections.map((s) => s.brand.brand), ['Vim', 'Aashirvaad', 'Lizol']);
      expect(feed.reachedEnd, isTrue);
    });

    test('shows every brand exactly once', () async {
      final catalogue = FakeCatalogue({for (var i = 0; i < 12; i++) 'Brand$i': 3});
      final feed = controllerFor(catalogue, anchor: 'Brand5');

      await feed.start();
      await scrollToEnd(feed);

      final names = feed.sections.map((s) => s.brand.brand).toList();
      expect(names.toSet(), hasLength(names.length), reason: 'a brand was appended twice');
      expect(names.toSet(), catalogue.productsPerBrand.keys.toSet());
    });

    test('never repeats a product', () async {
      final catalogue = FakeCatalogue({'Lizol': 25, 'Vim': 25, 'Surf': 25});
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();
      await scrollToEnd(feed);

      final ids = feed.sections.expand((s) => s.products).map((p) => p.id).toList();
      expect(ids.toSet(), hasLength(ids.length));
      expect(ids, hasLength(75));
    });

    test('a fast flick does not fire duplicate requests', () async {
      // Twenty scroll events land while one request is still open. Exactly
      // one request may be in flight, and none of the extra events may queue
      // a second copy of it.
      final catalogue = FakeCatalogue({'Lizol': 3, 'Vim': 3, 'Surf': 3});
      final feed = controllerFor(catalogue, anchor: 'Lizol');
      await feed.start();

      final gate = Completer<void>();
      catalogue.gate = gate;
      catalogue.requests.clear();

      final flicks = List.generate(20, (_) => feed.advance());
      gate.complete();
      catalogue.gate = null;
      await Future.wait(flicks);

      expect(catalogue.requests, hasLength(1));
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
    });

    test('the end message waits for the catalogue, not for one brand', () async {
      // The rule this exists to protect: "You've reached the end" must never
      // appear because a single brand ran out.
      final catalogue = FakeCatalogue({'Lizol': 1, 'Vim': 1, 'Surf': 1});
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();
      expect(feed.reachedEnd, isFalse);

      await feed.advance();
      expect(feed.reachedEnd, isFalse);

      await feed.advance();
      expect(feed.reachedEnd, isFalse, reason: 'all three shown, but not yet confirmed exhausted');

      await feed.advance();
      expect(feed.reachedEnd, isTrue);
    });

    test('advance does nothing once the catalogue is finished', () async {
      final catalogue = FakeCatalogue({'Lizol': 1});
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();
      await scrollToEnd(feed);
      catalogue.requests.clear();

      await feed.advance();
      await feed.advance();

      expect(catalogue.requests, isEmpty);
    });

    test('search stays inside the brand the page was opened for', () async {
      final catalogue = FakeCatalogue({'Lizol': 5, 'Vim': 5});
      final feed = controllerFor(catalogue, anchor: 'Lizol');
      await feed.start();

      await feed.setKeyword('Lizol item');
      await scrollToEnd(feed);

      expect(feed.isSearching, isTrue);
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol'],
          reason: 'a search must not pull in unrelated brands');
      expect(catalogue.requests.where((r) => r.startsWith('Vim')), isEmpty);
    });

    test('clearing the search restores the multi-brand feed', () async {
      final catalogue = FakeCatalogue({'Lizol': 3, 'Vim': 3});
      final feed = controllerFor(catalogue, anchor: 'Lizol');
      await feed.start();

      await feed.setKeyword('Lizol item');
      expect(feed.sections, hasLength(1));

      await feed.setKeyword('');
      await scrollToEnd(feed);

      expect(feed.isSearching, isFalse);
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
    });

    test('changing sort rebuilds without duplicating', () async {
      final catalogue = FakeCatalogue({'Lizol': 3, 'Vim': 3});
      final feed = controllerFor(catalogue, anchor: 'Lizol');
      await feed.start();
      await feed.advance();
      expect(feed.sections, hasLength(2));

      await feed.setSort(BrandSortOption.priceLowHigh);

      // Rebuilt from the anchor with the new sort, not appended to.
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol']);
      final ids = feed.sections.expand((s) => s.products).map((p) => p.id).toList();
      expect(ids.toSet(), hasLength(ids.length));

      // And the infinite-brand mechanism still works afterwards.
      await feed.advance();
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
    });

    test('re-choosing the same sort does not refetch', () async {
      final catalogue = FakeCatalogue({'Lizol': 3});
      final feed = controllerFor(catalogue, anchor: 'Lizol');
      await feed.start();
      catalogue.requests.clear();

      await feed.setSort(null); // already the default

      expect(catalogue.requests, isEmpty);
    });

    test('the in-stock filter rebuilds and keeps rolling into the next brand', () async {
      final catalogue = FakeCatalogue({'Lizol': 3, 'Vim': 3});
      final feed = controllerFor(catalogue, anchor: 'Lizol');
      await feed.start();

      await feed.setInStockOnly(true);
      expect(feed.inStockOnly, isTrue);
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol']);

      await feed.advance();
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
    });

    test('brands left empty by a filter are skipped, not shown as blank', () async {
      // An empty section would print a header over dead space.
      final catalogue = FakeCatalogue({'Lizol': 2, 'Empty': 0, 'Vim': 2});
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();
      await scrollToEnd(feed);

      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
      expect(feed.sections.every((s) => s.products.isNotEmpty), isTrue);
    });

    test('a long run of empty brands does not stall the feed', () async {
      // More consecutive empty brands than one batch will skip. Nothing gets
      // appended by that batch, so no scroll event follows and nothing would
      // ask for more - the feed has to keep itself going or it stalls on a
      // blank footer with neither products nor an end message.
      final catalogue = FakeCatalogue({
        'Lizol': 2,
        for (var i = 0; i < 9; i++) 'Empty$i': 0,
        'Vim': 2,
      });
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();
      await feed.advance();

      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
    });

    test('a catalogue that is entirely empty after the anchor still ends', () async {
      final catalogue = FakeCatalogue({
        'Lizol': 2,
        for (var i = 0; i < 9; i++) 'Empty$i': 0,
      });
      final feed = controllerFor(catalogue, anchor: 'Lizol');

      await feed.start();
      await feed.advance();

      expect(feed.sections.map((s) => s.brand.brand), ['Lizol']);
      expect(feed.reachedEnd, isTrue);
    });

    test('a failed page keeps what is already on screen', () async {
      final catalogue = FakeCatalogue({'Lizol': 3, 'Vim': 3});
      final feed = controllerFor(catalogue, anchor: 'Lizol');
      await feed.start();

      catalogue.failNextFetch = true;
      await feed.advance();

      expect(feed.errorMessage, isNotNull);
      expect(feed.sections.single.products, hasLength(3), reason: 'loaded products must survive');
      expect(feed.reachedEnd, isFalse, reason: 'a failure is not the end of the catalogue');

      // And a retry gets going again rather than dead-ending.
      await feed.advance();
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
    });

    test('a failed brand list degrades to the anchor brand alone', () async {
      final catalogue = FakeCatalogue({'Lizol': 3, 'Vim': 3});
      final feed = BrandFeedController(
        anchorBrand: const BrandSummary(brand: 'Lizol', productCount: 3),
        loadBrands: () async => throw StateError('brands endpoint down'),
        fetchPage: catalogue.fetchPage,
      );

      await feed.start();

      // Still browsable - exactly what this screen did before the feed
      // existed - rather than an error page.
      expect(feed.sections.single.brand.brand, 'Lizol');
      expect(feed.sections.single.products, hasLength(3));
      expect(feed.errorMessage, isNull);
    });

    test('an anchor missing from the catalogue is still shown first', () async {
      // Its last product went inactive, or the cached brand list is a moment
      // stale. The customer asked for this brand; show it.
      final catalogue = FakeCatalogue({'Vim': 2});
      final feed = BrandFeedController(
        anchorBrand: const BrandSummary(brand: 'Lizol', productCount: 1),
        loadBrands: catalogue.loadBrands,
        fetchPage: catalogue.fetchPage,
      );
      // Served by the product endpoint, absent from the brand list.
      catalogue.offCatalogue['Lizol'] = 1;

      await feed.start();
      await scrollToEnd(feed);

      expect(feed.sections.first.brand.brand, 'Lizol');
      expect(feed.sections.map((s) => s.brand.brand), ['Lizol', 'Vim']);
    });

    test('the brand order is fetched once per query, not once per brand', () async {
      final catalogue = FakeCatalogue({'A': 2, 'B': 2, 'C': 2, 'D': 2});
      final feed = controllerFor(catalogue, anchor: 'A');

      await feed.start();
      await scrollToEnd(feed);

      expect(catalogue.brandListCalls, 1);
    });
  });
}
