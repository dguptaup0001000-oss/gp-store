import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/presentation/auth_providers.dart';
import 'store_status.dart';
import 'store_status_repository.dart';

final storeStatusRepositoryProvider = Provider<StoreStatusRepository>((ref) {
  return StoreStatusRepository(apiClient: ref.watch(apiClientProvider));
});

/// The shop's state, refreshed while the app is open.
///
/// NOT autoDispose: this backs a banner on the home screen and the delivery
/// promise at checkout, so it is read from several screens across a session
/// and dropping it between them would mean a fetch per screen.
///
/// POLLED, NOT PUSHED. A websocket for one small fact would be a connection to
/// keep alive, reconnect and authenticate, for something a periodic GET answers
/// in a few hundred bytes — and the endpoint is cached ten seconds server side,
/// so a crowd of apps polling collapses into few queries. The interval is
/// deliberately shorter near closing time: for most of the day the answer
/// changes twice, but in the last quarter-hour it changes by the minute, and
/// that is the one moment a customer is actually reading it.
///
/// FAILS OPEN. A dropped request yields [StoreStatus.unknown], which says the
/// shop is open and makes no delivery promise — see that constructor for why
/// assuming "closed" is the far worse error to make.
final storeStatusProvider = StreamProvider<StoreStatus>((ref) async* {
  final repository = ref.watch(storeStatusRepositoryProvider);

  // THE POLL MUST DIE WITH THE PROVIDER. `await Future.delayed(...)` inside
  // this loop reads naturally and is wrong: disposing the provider pauses the
  // generator but does NOT cancel a timer already ticking, so a disposed
  // provider leaves a live 30-second timer holding this closure — and its
  // repository, and its client — until it fires. Flutter's test binding fails
  // the test outright for exactly this ("A Timer is still pending even after
  // the widget tree was disposed"), which is how it was caught; in the app it
  // is the quieter version of the same leak, one timer per disposal.
  //
  // So the wait races the delay against disposal and cancels the timer either
  // way. `disposed` is checked after the wait as well, because completing the
  // race is not the same as the loop being allowed to continue.
  final disposal = Completer<void>();
  var disposed = false;
  ref.onDispose(() {
    disposed = true;
    if (!disposal.isCompleted) disposal.complete();
  });

  while (!disposed) {
    StoreStatus status;
    try {
      status = await repository.fetch();
    } catch (_) {
      // Deliberately swallowed rather than surfaced as an error state: the
      // shop being unreachable for one poll must not put an error banner over
      // a catalogue the customer can still browse.
      status = StoreStatus.unknown();
    }
    if (disposed) return;
    yield status;

    // Five seconds while the closing countdown runs, thirty otherwise. The
    // server's ten-second cache means the faster poll costs one query per ten
    // seconds shop-wide, not one per customer.
    final tick = Completer<void>();
    final timer = Timer(
      status.countdownActive
          ? const Duration(seconds: 5)
          : const Duration(seconds: 30),
      () {
        if (!tick.isCompleted) tick.complete();
      },
    );
    await Future.any<void>(<Future<void>>[tick.future, disposal.future]);
    timer.cancel();
  }
});
