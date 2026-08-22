import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/category_feed_controller.dart';

/// A fake catalogue: category id -> how many products it holds.
class _FakeCatalogue {
  _FakeCatalogue(this.productsPerCategory, {this.pageSize = 20});

  final Map<int, int> productsPerCategory;
  final int pageSize;

  /// Every request made, so "one scroll is one request" and "no duplicate
  /// requests" are assertions rather than hopes.
  final List<({int categoryId, int page})> requests = [];

  Future<List<Product>> fetch({
    required int categoryId,
    required int page,
    required int size,
  }) async {
    requests.add((categoryId: categoryId, page: page));
    final total = productsPerCategory[categoryId] ?? 0;
    final start = page * size;
    if (start >= total) return const [];

    final end = (start + size) > total ? total : (start + size);
    return [
      for (var i = start; i < end; i++)
        Product(id: categoryId * 100000 + i, name: 'c$categoryId-p$i'),
    ];
  }
}

Category _category(int id) => Category(id: id, name: 'Category $id');

void main() {
  group('CategoryFeedController', () {
    test('starts on the category the customer opened', () async {
      final catalogue = _FakeCatalogue({1: 5, 2: 5, 3: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(2),
        allCategories: [_category(1), _category(2), _category(3)],
        fetchPage: catalogue.fetch,
      );

      await controller.start();

      expect(controller.sections.first.category.id, 2,
          reason: 'the feed must open on the category that was tapped');
    });

    test('rolls into the next category instead of stopping', () async {
      // 5 products in a page size of 20 means category 1 is exhausted by its
      // first page - the exact condition that used to end the scroll.
      final catalogue = _FakeCatalogue({1: 5, 2: 5, 3: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [_category(1), _category(2), _category(3)],
        fetchPage: catalogue.fetch,
      );

      await controller.start();
      expect(controller.sections.length, 1);
      expect(controller.reachedEnd, isFalse,
          reason: 'category 1 ran out, but two categories remain');

      await controller.advance();

      expect(controller.sections.length, 2);
      expect(controller.sections[1].category.id, 2);
    });

    test('pages within a category before moving to the next', () async {
      final catalogue = _FakeCatalogue({1: 45, 2: 5}, pageSize: 20);
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [_category(1), _category(2)],
        fetchPage: catalogue.fetch,
      );

      await controller.start();
      await controller.advance();

      expect(controller.sections.length, 1,
          reason: 'category 1 still has pages left, so nothing new should be appended');
      expect(controller.sections.first.products.length, 40);
    });

    test('wraps past the end so every category is reachable', () async {
      final catalogue = _FakeCatalogue({1: 5, 2: 5, 3: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(3), // the LAST category in the rail
        allCategories: [_category(1), _category(2), _category(3)],
        fetchPage: catalogue.fetch,
      );

      await controller.start();
      await controller.advance();
      await controller.advance();

      final ids = controller.sections.map((s) => s.category.id).toList();
      expect(ids, [3, 1, 2],
          reason: 'opening the last category must wrap to the start, not end immediately');
    });

    test('an empty category is skipped, not shown as a blank section', () async {
      final catalogue = _FakeCatalogue({1: 5, 2: 0, 3: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [_category(1), _category(2), _category(3)],
        fetchPage: catalogue.fetch,
      );

      await controller.start();
      await controller.advance();

      final ids = controller.sections.map((s) => s.category.id).toList();
      expect(ids, [1, 3], reason: 'the empty category 2 should be skipped silently');
    });

    test('a run of empty categories does not stall the feed', () async {
      final catalogue = _FakeCatalogue({1: 5, 2: 0, 3: 0, 4: 0, 5: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [for (var i = 1; i <= 5; i++) _category(i)],
        fetchPage: catalogue.fetch,
      );

      await controller.start();
      await controller.advance();

      expect(controller.sections.map((s) => s.category.id), [1, 5]);
    });

    test('the feed ends exactly once, when every category is exhausted', () async {
      final catalogue = _FakeCatalogue({1: 5, 2: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [_category(1), _category(2)],
        fetchPage: catalogue.fetch,
      );

      await controller.start();
      await controller.advance();
      expect(controller.reachedEnd, isFalse);

      await controller.advance();
      expect(controller.reachedEnd, isTrue);

      // And once ended, further scrolling must not keep asking. An infinite
      // request loop at the bottom of a list is the classic version of this
      // bug and it is invisible until someone reads a network log.
      final requestsAtEnd = catalogue.requests.length;
      await controller.advance();
      await controller.advance();
      expect(catalogue.requests.length, requestsAtEnd,
          reason: 'advance() after the end must make no further requests');
    });

    test('a product filed under two categories appears only once', () async {
      // Duplicate keys in a lazy list are a hard crash, not a cosmetic issue.
      final catalogue = _FakeCatalogue({1: 5, 2: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [_category(1), _category(2)],
        fetchPage: (({required categoryId, required page, required size}) async {
          final products = await catalogue.fetch(
              categoryId: categoryId, page: page, size: size);
          // Force an overlap: category 2 returns one of category 1's products.
          if (categoryId == 2 && page == 0 && products.isNotEmpty) {
            return [const Product(id: 100000, name: 'shared'), ...products];
          }
          return products;
        }),
      );

      await controller.start();
      await controller.advance();

      final allIds = controller.sections.expand((s) => s.products).map((p) => p.id).toList();
      expect(allIds.toSet().length, allIds.length, reason: 'a product was appended twice');
    });

    test('one scroll makes one request, not a burst', () async {
      final catalogue = _FakeCatalogue({1: 100});
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [_category(1)],
        fetchPage: catalogue.fetch,
      );

      await controller.start();
      final afterStart = catalogue.requests.length;

      await controller.advance();

      expect(catalogue.requests.length, afterStart + 1,
          reason: 'a single advance must fetch a single page');
    });

    test('the sidebar can tell which category a scroll position is in', () async {
      final catalogue = _FakeCatalogue({1: 5, 2: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [_category(1), _category(2)],
        fetchPage: catalogue.fetch,
      );

      await controller.start();
      await controller.advance();

      expect(controller.categoryAtProductIndex(0)?.id, 1);
      expect(controller.categoryAtProductIndex(4)?.id, 1);
      expect(controller.categoryAtProductIndex(5)?.id, 2,
          reason: 'the sixth product is the first of category 2');
      expect(controller.productIndexOfCategory(2), 5,
          reason: 'tapping category 2 in the rail should jump to flat index 5');
    });

    test('a failed page keeps everything already on screen', () async {
      var failNext = false;
      final catalogue = _FakeCatalogue({1: 5, 2: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [_category(1), _category(2)],
        fetchPage: (({required categoryId, required page, required size}) async {
          if (failNext) throw StateError('network down');
          return catalogue.fetch(categoryId: categoryId, page: page, size: size);
        }),
      );

      await controller.start();
      expect(controller.sections.length, 1);

      failNext = true;
      await controller.advance();

      expect(controller.sections.length, 1,
          reason: 'the products already loaded must survive a failed page');
      expect(controller.errorMessage, isNotNull);
      expect(controller.reachedEnd, isFalse,
          reason: 'a failure is not the end of the catalogue - retry must remain possible');
    });

    test('a successful retry clears the failure message', () async {
      var failNext = false;
      final catalogue = _FakeCatalogue({1: 5, 2: 5});
      final controller = CategoryFeedController(
        anchorCategory: _category(1),
        allCategories: [_category(1), _category(2)],
        fetchPage: (({required categoryId, required page, required size}) async {
          if (failNext) throw StateError('network down');
          return catalogue.fetch(categoryId: categoryId, page: page, size: size);
        }),
      );

      await controller.start();

      failNext = true;
      await controller.advance();
      expect(controller.errorMessage, isNotNull);

      failNext = false;
      await controller.advance();

      expect(controller.errorMessage, isNull,
          reason: 'a retry that works must not leave the old failure under the last row');
      expect(controller.sections.length, 2,
          reason: 'the retry should have appended the category that failed');
    });
  });
}
