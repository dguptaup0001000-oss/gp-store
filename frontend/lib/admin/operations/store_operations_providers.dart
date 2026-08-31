import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/presentation/auth_providers.dart';
import '../../features/admin/presentation/admin_providers.dart';
import 'store_operations_models.dart';
import 'store_operations_repository.dart';

final storeOperationsRepositoryProvider =
    Provider<StoreOperationsRepository>((ref) {
  return StoreOperationsRepository(apiClient: ref.watch(apiClientProvider));
});

/// The switch, the schedule and the closed days.
///
/// autoDispose, like every other admin provider: the console is a drill-down,
/// and a screen visited once in a session should not keep its data for the
/// rest of it.
final storeOperationsProvider =
    FutureProvider.autoDispose<StoreOperations>((ref) {
  return ref.watch(storeOperationsRepositoryProvider).getOperations();
});

/// The day being packed. Null means "whatever the server says is next", which
/// overnight is today's 09:00 run.
final preparationDateProvider = StateProvider<DateTime?>((ref) => null);

final preparationListProvider =
    FutureProvider.autoDispose<PreparationList>((ref) {
  return ref.watch(storeOperationsRepositoryProvider).getPreparation(
        date: ref.watch(preparationDateProvider),
      );
});

/// Same-day versus next-morning over the dashboard's own period.
///
/// Reads [analyticsPeriodDaysProvider] rather than keeping a period of its own, so
/// this panel can never be showing a different window from the revenue chart
/// sitting above it.
final deliveryTypeSharesProvider =
    FutureProvider.autoDispose<List<DeliveryTypeShare>>((ref) {
  return ref
      .watch(storeOperationsRepositoryProvider)
      .getDeliveryTypeShares(ref.watch(analyticsPeriodDaysProvider));
});
