import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/data/products_repository.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';

/// Counts how many times the category preview actually hits the repository.
class CountingRepository implements ProductsRepository {
  int browseCalls = 0;
  final List<int> categoriesRequested = [];
  final List<int> sizesRequested = [];

  @override
  Future<List<Product>> browseByCategory(int categoryId, {int page = 0, int size = 20}) async {
    browseCalls++;
    categoriesRequested.add(categoryId);
    sizesRequested.add(size);
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

  group('categoryPreviewProvider', () {
    test('reading the same category repeatedly issues ONE request', () async {
      // The bug this replaced: the collage built its future inside build(),
      // so a new future - and a new request - was created on every rebuild,
      // once per category tile.
      await container.read(categoryPreviewProvider(7).future);
      container.read(categoryPreviewProvider(7));
      container.read(categoryPreviewProvider(7));
      container.read(categoryPreviewProvider(7));

      expect(repository.browseCalls, 1);
    });

    test('different categories are fetched independently', () async {
      await container.read(categoryPreviewProvider(1).future);
      await container.read(categoryPreviewProvider(2).future);
      await container.read(categoryPreviewProvider(1).future);

      expect(repository.browseCalls, 2);
      expect(repository.categoriesRequested, [1, 2]);
    });

    test('six category tiles cost six requests, not six per rebuild', () async {
      // What the home screen actually does: six tiles, each watching its own
      // category. Rebuilding the screen must not multiply that.
      for (var categoryId = 1; categoryId <= 6; categoryId++) {
        await container.read(categoryPreviewProvider(categoryId).future);
      }
      expect(repository.browseCalls, 6);

      // Three more "rebuilds" worth of reads.
      for (var rebuild = 0; rebuild < 3; rebuild++) {
        for (var categoryId = 1; categoryId <= 6; categoryId++) {
          container.read(categoryPreviewProvider(categoryId));
        }
      }

      expect(repository.browseCalls, 6, reason: 'rebuilds must not re-issue requests');
    });

    test('asks for a small page, not a full category', () async {
      // It is a 2x2 collage. Fetching a default page of 20 products to show
      // four images is bandwidth nobody sees, multiplied by six tiles.
      await container.read(categoryPreviewProvider(3).future);
      expect(repository.sizesRequested, [4]);
    });
  });
}
