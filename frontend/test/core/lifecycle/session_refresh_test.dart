import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/lifecycle/session_refresh.dart';
import 'package:gpstore/features/products/data/products_repository.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';

class CountingRepository implements ProductsRepository {
  int offersCalls = 0;
  int categoryCalls = 0;

  @override
  Future<List<Coupon>> getActiveOffers() async {
    offersCalls++;
    return const [];
  }

  @override
  Future<List<Category>> getCategories() async {
    categoryCalls++;
    return const [];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  late CountingRepository repository;

  Future<void> pumpApp(WidgetTester tester, {Duration staleAfter = const Duration(minutes: 5)}) async {
    repository = CountingRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [productsRepositoryProvider.overrideWithValue(repository)],
        child: SessionRefresh(
          staleAfter: staleAfter,
          onStaleResume: (ref) => ref.invalidate(activeOffersProvider),
          child: MaterialApp(
            home: Consumer(
              builder: (context, ref, _) {
                // Something has to be WATCHING for an invalidation to cost a
                // refetch - which is the behaviour being tested.
                ref.watch(activeOffersProvider);
                ref.watch(categoriesProvider);
                return const SizedBox.shrink();
              },
            ),
          ),
        ),
      ),
    );
    await tester.pump();
  }

  void background(WidgetTester tester) =>
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);

  void resume(WidgetTester tester) =>
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);

  group('what a resume costs', () {
    testWidgets('a brief switch away costs nothing', (tester) async {
      // Glancing at a notification and coming straight back is a resume too.
      // Refetching on every one of those would put a counter phone that is
      // picked up and put down all day into a loop.
      await pumpApp(tester, staleAfter: const Duration(minutes: 5));
      final before = repository.offersCalls;

      background(tester);
      resume(tester);
      await tester.pump();

      expect(repository.offersCalls, before, reason: 'no refetch for a momentary switch');
    });

    testWidgets('a real absence refreshes what actually goes stale', (tester) async {
      // staleAfter zero: any measurable gap counts, which is the same code
      // path a genuine eight-hour absence takes.
      await pumpApp(tester, staleAfter: Duration.zero);
      final before = repository.offersCalls;

      background(tester);
      await tester.pump(const Duration(milliseconds: 10));
      resume(tester);
      await tester.pump();

      expect(repository.offersCalls, greaterThan(before),
          reason: 'time-limited offers must not still be showing yesterday');
    });

    testWidgets('resume does NOT re-fetch the whole catalogue', (tester) async {
      // The failure mode this guards against is the fix becoming the
      // problem: resume is exactly where it would be easy to fire every
      // provider at once and rebuild the request storm on a different
      // trigger.
      await pumpApp(tester, staleAfter: Duration.zero);
      final categoriesBefore = repository.categoryCalls;

      background(tester);
      await tester.pump(const Duration(milliseconds: 10));
      resume(tester);
      await tester.pump();

      expect(repository.categoryCalls, categoriesBefore,
          reason: 'a grocery catalogue does not restructure while the app is backgrounded');
    });

    testWidgets('an inactive blip is not treated as leaving', (tester) async {
      // inactive fires for a notification shade or an incoming call. Starting
      // the away-clock there would make every glance look like an absence.
      await pumpApp(tester, staleAfter: Duration.zero);
      final before = repository.offersCalls;

      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      resume(tester);
      await tester.pump();

      expect(repository.offersCalls, before);
    });

    testWidgets('resuming without ever having been backgrounded does nothing', (tester) async {
      await pumpApp(tester, staleAfter: Duration.zero);
      final before = repository.offersCalls;

      resume(tester);
      await tester.pump();

      expect(repository.offersCalls, before);
    });
  });
}
