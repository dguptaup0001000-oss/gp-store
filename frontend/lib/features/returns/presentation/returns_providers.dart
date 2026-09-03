import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/returns_repository.dart';
import '../domain/return_models.dart';

final returnsRepositoryProvider = Provider<ReturnsRepository>((ref) {
  return ReturnsRepository(apiClient: ref.watch(apiClientProvider));
});

/// What is still returnable on one order.
///
/// autoDispose and family: it is asked once, while a customer fills in the
/// form, and is stale the moment they submit.
final returnableLinesProvider =
    FutureProvider.autoDispose.family<Map<int, int>, int>((ref, orderId) {
  return ref.watch(returnsRepositoryProvider).returnableLines(orderId);
});

/// The customer's own returns.
final myReturnsProvider =
    FutureProvider.autoDispose<List<ReturnRequest>>((ref) {
  return ref.watch(returnsRepositoryProvider).mine();
});

/// The shop's queue. Staff only - the backend refuses this to a shopper.
final pendingReturnsProvider =
    FutureProvider.autoDispose<List<ReturnRequest>>((ref) {
  return ref.watch(returnsRepositoryProvider).pending();
});
