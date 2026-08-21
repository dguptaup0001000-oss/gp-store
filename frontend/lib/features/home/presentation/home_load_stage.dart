import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../products/presentation/products_providers.dart';

/// Whether the home screen's below-the-fold content may start loading yet.
///
/// WHY THIS EXISTS. Opening the app fired eight requests at once - categories,
/// brands, offers, new arrivals, trending, recommended, the product feed and
/// the cart - against a backend measured at 100% CPU under load. Three of
/// those eight were for carousels roughly two screens down, and one was the
/// endless feed at the very bottom. The customer waits on all eight to clear
/// the queue before seeing any of the four things actually on screen.
///
/// WHY NOT JUST RELY ON LAZY SLIVERS. The obvious fix is to move each watch
/// into a Consumer further down the list and let SliverList build it only
/// when it scrolls near. That does not work here, and the reason is worth
/// recording: SliverList builds children until it has filled the viewport,
/// and at cold start every section above the carousels is still loading and
/// collapses to zero height. The list walks straight past them and builds
/// the carousels on the first frame - exactly when the deferral was supposed
/// to help. Laziness that evaporates under load is worse than none, because
/// it looks like it works.
///
/// SO THE GATE IS EXPLICIT: the second wave starts when the first has
/// SETTLED, not when it has succeeded. hasError counts, so a failed
/// categories call cannot wedge the rest of the page shut - the sections
/// below still load, and each keeps its own retry.
///
/// hasValue stays true through a refresh (Riverpod carries the previous
/// value into AsyncLoading), so pull-to-refresh does not slam the gate and
/// flip loaded carousels back to spinners.
final homeBelowFoldReadyProvider = Provider<bool>((ref) {
  bool settled(AsyncValue<Object?> value) => value.hasValue || value.hasError;

  return settled(ref.watch(categoriesProvider)) &&
      settled(ref.watch(brandsProvider)) &&
      settled(ref.watch(activeOffersProvider));
});
