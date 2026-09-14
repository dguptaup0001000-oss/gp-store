import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_models.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';

/// The furniture every "which shops are near me" screen needs.
///
/// SHARED BECAUSE THE LADDER IS ONE IDEA. The shop picker, a category's shops
/// and anything added later are all asking the same server the same question
/// with a different filter on it, and each rendering its own "search farther"
/// button is how three screens end up disagreeing about what farther means -
/// or worse, one of them inventing a radius the server never offered.

/// "No shops within 8 km. Showing results within 20 km."
///
/// THE SENTENCE IS THE SERVER'S. The widening decision and the words
/// describing it are made in the same place, so a change to the ladder cannot
/// leave the app telling customers something that is no longer true. Nothing
/// is drawn when the server did not widen.
class WidenedNotice extends StatelessWidget {
  const WidenedNotice({super.key, required this.page});

  final DiscoveryPage page;

  @override
  Widget build(BuildContext context) {
    final message = page.message;
    if (!page.widened || message == null || message.isEmpty) {
      return const SizedBox.shrink();
    }
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.secondary.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          const Icon(Icons.travel_explore_outlined,
              size: 18, color: AppColors.secondary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

/// The next rung of the server's ladder, offered as a button.
///
/// THE APP DOES NOT PICK THE NUMBER. `nextRadiusKm` is what the server says
/// comes after what it just searched; when it is null there is nowhere farther
/// to go and this draws nothing rather than offering a search that would
/// return the same list.
class SearchFarther extends ConsumerWidget {
  const SearchFarther({super.key, required this.page});

  final DiscoveryPage page;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final next = page.nextRadiusKm;
    final searchedFar = ref.watch(discoveryRadiusProvider) != null;

    return Padding(
      padding: const EdgeInsets.only(top: 4, bottom: 24),
      child: Column(
        children: [
          if (next != null)
            OutlinedButton.icon(
              onPressed: hapticize(() =>
                  ref.read(discoveryRadiusProvider.notifier).searchFarther(next)),
              icon: const Icon(Icons.expand_more, size: 18),
              label: Text('Search within ${formatKm(next)} km'),
            ),
          if (searchedFar) ...[
            const SizedBox(height: 4),
            TextButton(
              onPressed: hapticize(
                  () => ref.read(discoveryRadiusProvider.notifier).backToLocal()),
              child: const Text('Only shops that deliver to me'),
            ),
          ],
        ],
      ),
    );
  }
}

/// Nothing came back at this rung, said in the caller's own words.
///
/// LOCAL-FIRST IS NOT LOCAL-ONLY. A shop appears in the unwidened list only
/// when the address is inside THAT SHOP'S OWN declared delivery radius, which
/// is the shop's decision and not the platform's - so the first answer really
/// can be "nobody delivers here". What matters is that it is not the end of
/// the conversation: the customer can look farther, see that shops exist, and
/// decide for themselves.
///
/// THE WORDING IS THE CALLER'S because "no shop delivers here" and "no chemist
/// near you" are different disappointments with different next steps, and one
/// generic sentence for both tells the customer less than either.
class NothingFoundHere extends ConsumerWidget {
  const NothingFoundHere({
    super.key,
    required this.page,
    required this.title,
    required this.body,
    this.icon = Icons.storefront_outlined,
    this.also,
  });

  final DiscoveryPage page;
  final String title;
  final String body;
  final IconData icon;

  /// One more way out, when the caller has one to offer.
  final Widget? also;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final next = page.nextRadiusKm;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 44, color: AppColors.textSecondary),
            const SizedBox(height: 12),
            Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            Text(
              // The server's sentence when it has one - it knows how far it
              // actually looked - and the caller's when it does not.
              page.message ?? body,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textSecondary, fontSize: 13),
            ),
            const SizedBox(height: 16),
            if (next != null)
              FilledButton.icon(
                onPressed: hapticize(() =>
                    ref.read(discoveryRadiusProvider.notifier).searchFarther(next)),
                icon: const Icon(Icons.travel_explore_outlined, size: 18),
                label: Text('Look within ${formatKm(next)} km'),
              ),
            if (also != null) ...[const SizedBox(height: 8), also!],
          ],
        ),
      ),
    );
  }
}

/// 8 rather than 8.0, and 2.5 rather than 2.
String formatKm(double value) =>
    value == value.roundToDouble() ? value.toStringAsFixed(0) : value.toStringAsFixed(1);
