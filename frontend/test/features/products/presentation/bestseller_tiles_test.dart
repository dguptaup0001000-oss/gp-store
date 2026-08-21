import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/data/products_repository.dart';
import 'package:gpstore/features/products/domain/bestseller_models.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';

class CountingRepository implements ProductsRepository {
  int bestsellerCalls = 0;
  int browseCalls = 0;
  int? lastCategories;
  int? lastPerCategory;
  List<BestsellerTile> result = const [];

  @override
  Future<List<BestsellerTile>> getBestsellerTiles({int categories = 6, int perCategory = 4}) async {
    bestsellerCalls++;
    lastCategories = categories;
    lastPerCategory = perCategory;
    return result;
  }

  @override
  Future<List<Product>> browseByCategory(int categoryId, {int page = 0, int size = 20}) async {
    browseCalls++;
    return const [];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  late CountingRepository repository;
  late ProviderContainer container;

  setUp(() {
    repository = CountingRepository();
    container = ProviderContainer(
      overrides: [productsRepositoryProvider.overrideWithValue(repository)],
    );
  });

  tearDown(() => container.dispose());

  group('how many requests the collage costs', () {
    test('the whole collage is ONE request, not one per category', () async {
      // The bug this replaced: six tiles meant six HTTP calls on every cold
      // home open, to render twenty-four thumbnails.
      await container.read(bestsellerTilesProvider.future);

      expect(repository.bestsellerCalls, 1);
      expect(repository.browseCalls, 0, reason: 'no per-category call should survive');
    });

    test('re-reading it does not re-request', () async {
      await container.read(bestsellerTilesProvider.future);
      container.read(bestsellerTilesProvider);
      container.read(bestsellerTilesProvider);

      expect(repository.bestsellerCalls, 1);
    });

    test('it asks for what the UI draws, not a page of twenty', () async {
      await container.read(bestsellerTilesProvider.future);

      expect(repository.lastCategories, 6);
      expect(repository.lastPerCategory, 4);
    });
  });

  group('what the client does with a broken payload', () {
    test('a tile with no products is dropped rather than drawn empty', () {
      final tile = BestsellerTile.fromJson(const {
        'categoryId': 3,
        'categoryName': 'Snacks',
        'productIds': <dynamic>[],
        'imageUrls': <dynamic>[],
      });

      expect(tile.isRenderable, isFalse);
    });

    test('a tile with no name is dropped - an unlabelled square means nothing', () {
      final tile = BestsellerTile.fromJson(const {
        'categoryId': 3,
        'productIds': [1],
        'imageUrls': ['a.jpg'],
      });

      expect(tile.isRenderable, isFalse);
    });

    test('missing fields do not throw - the collage is the least important thing on the screen', () {
      // Losing four thumbnails to a malformed payload is acceptable; losing
      // the entire home screen to an uncaught parse error is not.
      final tile = BestsellerTile.fromJson(const <String, dynamic>{});

      expect(tile.categoryId, 0);
      expect(tile.categoryName, '');
      expect(tile.productIds, isEmpty);
      expect(tile.isRenderable, isFalse);
    });

    test('fewer imageUrls than productIds still lines up positionally', () {
      // The two lists are read in parallel by index. If a backend change
      // ever let them drift, the collage would draw one product's photo in
      // another product's square - or throw a range error mid-build.
      final tile = BestsellerTile.fromJson(const {
        'categoryId': 1,
        'categoryName': 'Atta',
        'productIds': [10, 11, 12, 13],
        'imageUrls': ['a.jpg', 'b.jpg'],
      });

      expect(tile.productIds, hasLength(4));
      expect(tile.imageUrls, hasLength(4));
      expect(tile.imageUrls[2], isNull);
      expect(tile.imageUrls[3], isNull);
    });

    test('a null image keeps its slot instead of shifting the others', () {
      final tile = BestsellerTile.fromJson(const {
        'categoryId': 1,
        'categoryName': 'Atta',
        'productIds': [10, 11],
        'imageUrls': [null, 'b.jpg'],
      });

      expect(tile.imageUrls, [null, 'b.jpg'],
          reason: 'dropping the null would move b.jpg into the first square');
    });
  });

  group('which categories a customer may see', categoryVisibilityTests);

  group('when the backend is down', () {
    test('the failure surfaces as an error rather than an empty collage', () async {
      // An empty list and a failed request must not look the same: one is
      // "this store has no bestsellers", the other is "try again".
      final failing = ProviderContainer(overrides: [
        productsRepositoryProvider.overrideWithValue(_FailingRepository()),
      ]);
      addTearDown(failing.dispose);

      await expectLater(
        failing.read(bestsellerTilesProvider.future),
        throwsA(isA<StateError>()),
      );
    });
  });
}

class _FailingRepository implements ProductsRepository {
  @override
  Future<List<BestsellerTile>> getBestsellerTiles({int categories = 6, int perCategory = 4}) async {
    throw StateError('bestsellers endpoint down');
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Separate from the collage tests above: this is about which categories a
/// customer is allowed to see at all.
class _CategoryRepository implements ProductsRepository {
  _CategoryRepository(this.categories);
  final List<Category> categories;

  @override
  Future<List<Category>> getCategories() async => categories;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Category _category(int id, String name, {bool active = true}) =>
    Category(id: id, name: name, active: active);

void categoryVisibilityTests() {
  test('a deactivated category never reaches the customer', () async {
    // The backend's findAll() returns inactive categories too. One screen
    // filtered them client-side and the home rows did not, so turning a
    // category off hid it in one place and left it advertised in three.
    final container = ProviderContainer(overrides: [
      productsRepositoryProvider.overrideWithValue(_CategoryRepository([
        _category(1, 'Atta'),
        _category(2, 'Discontinued', active: false),
        _category(3, 'Dairy'),
      ])),
    ]);
    addTearDown(container.dispose);

    final visible = await container.read(categoriesProvider.future);

    expect(visible.map((c) => c.name), ['Atta', 'Dairy']);
  });

  test('the order is stable, so "the first six" means something', () async {
    // findAll() has no ORDER BY. The collage takes the first six, so
    // without this the tiles could reshuffle after any unrelated update.
    final container = ProviderContainer(overrides: [
      productsRepositoryProvider.overrideWithValue(_CategoryRepository([
        _category(9, 'Snacks'),
        _category(2, 'Dairy'),
        _category(5, 'Rice'),
      ])),
    ]);
    addTearDown(container.dispose);

    final visible = await container.read(categoriesProvider.future);

    expect(visible.map((c) => c.id), [2, 5, 9]);
  });
}
