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

  while (true) {
    StoreStatus status;
    try {
      status = await repository.fetch();
    } catch (_) {
      // Deliberately swallowed rather than surfaced as an error state: the
      // shop being unreachable for one poll must not put an error banner over
      // a catalogue the customer can still browse.
      status = StoreStatus.unknown();
    }
    yield status;

    // Five seconds while the closing countdown runs, thirty otherwise. The
    // server's ten-second cache means the faster poll costs one query per ten
    // seconds shop-wide, not one per customer.
    await Future<void>.delayed(
      status.countdownActive
          ? const Duration(seconds: 5)
          : const Duration(seconds: 30),
    );
  }
});
