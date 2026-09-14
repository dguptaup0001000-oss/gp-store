import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// What a slow section is allowed to rebuild when it finally lands.
///
/// THE REGRESSION THIS EXISTS TO CATCH. Watching a section's provider at the
/// top of HomeScreen.build is the natural thing to write and it costs the
/// whole page: when the offers arrive, Riverpod rebuilds HomeScreen, which
/// rebuilds the CustomScrollView, the sliver list and every section in it -
/// three times on a cold open, once per first-wave provider. The home screen
/// did exactly that. Moving each watch into its own Consumer means the offers
/// landing rebuilds the offers.
///
/// READS THE SOURCE rather than counting rebuilds, for the same reason
/// ShopSwitchClearsEverythingTest does: there is no public hook that reports
/// "this element rebuilt", and a test that inferred it from behaviour would
/// pass for the wrong reason the first time a section happened to be cheap.
/// What is actually being protected is a rule about where a line may go, so
/// that is what is checked, and the failure names the line.
void main() {
  /// Providers whose answer belongs to ONE SECTION of the page.
  ///
  /// The gate, the feed and the sign-in state are deliberately absent: those
  /// decide what the page may request and what it is allowed to draw, which
  /// is a decision about the page.
  const sectionProviders = [
    'activeOffersProvider',
    'brandsProvider',
    'categoriesProvider',
    'trendingProvider',
    'newArrivalsProvider',
    'recommendedForMeProvider',
  ];

  test('no section provider is watched at page level', () {
    final source =
        File('lib/features/home/presentation/home_screen.dart').readAsLinesSync();

    final offenders = <String>[];
    for (var i = 0; i < source.length; i++) {
      final line = source[i];
      final watched = sectionProviders
          .where((provider) => line.contains('ref.watch($provider)'));
      if (watched.isEmpty) continue;

      // Walk back to whichever came last: the page's own build, or a
      // Consumer's. A watch under a Consumer rebuilds that Consumer; a watch
      // under the page's build rebuilds the page.
      var insideAConsumer = false;
      for (var back = i; back >= 0; back--) {
        if (source[back].contains('Consumer(')) {
          insideAConsumer = true;
          break;
        }
        if (source[back].contains('Widget build(BuildContext context, WidgetRef ref)')) {
          break;
        }
      }
      if (!insideAConsumer) {
        offenders.add('line ${i + 1}: ${line.trim()}');
      }
    }

    expect(
      offenders,
      isEmpty,
      reason: 'These watches are at page level, so the provider landing '
          'rebuilds the whole home screen - the CustomScrollView, the sliver '
          'list and every other section with it. Wrap each in a Consumer so '
          'only its own section rebuilds:\n${offenders.join('\n')}',
    );
  });

  test('the parser can actually see a page-level watch', () {
    // A GUARD ON THE GUARD. The check above passes trivially if the walk-back
    // never finds anything, and a test that cannot fail is worse than no test
    // - so this is the same rule run against a page-level watch that is known
    // to be one.
    const badSource = [
      '  Widget build(BuildContext context, WidgetRef ref) {',
      '    final offersAsync = ref.watch(activeOffersProvider);',
    ];

    var insideAConsumer = false;
    for (var back = 1; back >= 0; back--) {
      if (badSource[back].contains('Consumer(')) {
        insideAConsumer = true;
        break;
      }
      if (badSource[back]
          .contains('Widget build(BuildContext context, WidgetRef ref)')) {
        break;
      }
    }
    expect(insideAConsumer, isFalse);
  });
}
