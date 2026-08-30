import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Refreshes the few things that genuinely go stale while the app sits in
/// the background.
///
/// THE APP HAD NO LIFECYCLE HANDLING AT ALL - no WidgetsBindingObserver
/// anywhere. That matters here more than in most apps because this one runs
/// on a shop counter phone that is backgrounded and resumed all day, and
/// because the catalogue providers are deliberately not autoDispose: their
/// data lives for the whole session. Resume after eight hours and the cart
/// badge, the totals bar and the offers strip were all showing yesterday.
///
/// WHAT IS DELIBERATELY *NOT* REFRESHED, because the brief's warning about
/// introducing a second request storm applies most sharply here - resume is
/// exactly the moment it would be easy to fire nine requests at once:
///
///   - categories, brands, bestsellers: a grocery catalogue's structure does
///     not change while someone has the app open, and re-fetching it buys
///     nothing.
///   - the product feed: invalidating it would throw away every page the
///     customer had scrolled and drop them back at the top.
///   - trending / new arrivals / recommended: slow-moving by construction,
///     and each one is a request for content that is probably off screen.
///
/// The customer APK passes cart + offers invalidation. The admin APK passes
/// nothing - it has no shopping cart, and importing cart providers here
/// would pull the shop graph into the staff APK.
///
/// And only after a real gap. Flicking to another app to check a message and
/// coming straight back is a resume too, and refetching on every one of
/// those would put the counter phone in a loop.
class SessionRefresh extends ConsumerStatefulWidget {
  const SessionRefresh({
    super.key,
    required this.child,
    this.onStaleResume,
    this.staleAfter = const Duration(minutes: 5),
  });

  final Widget child;

  /// How long the app must have been away before a resume is worth a refetch.
  final Duration staleAfter;

  /// Customer: invalidate cart + offers. Admin: omit.
  final void Function(WidgetRef ref)? onStaleResume;

  @override
  ConsumerState<SessionRefresh> createState() => _SessionRefreshState();
}

class _SessionRefreshState extends ConsumerState<SessionRefresh>
    with WidgetsBindingObserver {
  DateTime? _leftAt;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    // Removed as carefully as it was added: a lingering observer on a
    // disposed State is a leak that only shows up as a crash much later,
    // when the framework calls back into a widget that no longer exists.
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.paused:
      case AppLifecycleState.detached:
      case AppLifecycleState.hidden:
        _leftAt ??= DateTime.now();
      case AppLifecycleState.resumed:
        _refreshIfStale();
      case AppLifecycleState.inactive:
        // Transitional - a notification shade pulled down, a call arriving.
        // Not a real backgrounding, and starting the clock here would make
        // every glance at a notification look like an absence.
        break;
    }
  }

  void _refreshIfStale() {
    final leftAt = _leftAt;
    _leftAt = null;
    if (leftAt == null) return;
    if (DateTime.now().difference(leftAt) < widget.staleAfter) return;

    // invalidate, not refresh: this marks them stale and lets whatever is on
    // screen re-request what it is actually showing. ref.refresh would build
    // a provider nothing is watching, which is how a resume turns into a
    // request for a screen the customer cannot see.
    widget.onStaleResume?.call(ref);
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
