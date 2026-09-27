import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/demand_repository.dart';

final demandRepositoryProvider = Provider<DemandRepository>((ref) {
  return DemandRepository(apiClient: ref.watch(apiClientProvider));
});

final myDemandRequestsProvider =
    FutureProvider.autoDispose<List<DemandRequest>>((ref) {
  return ref.watch(demandRepositoryProvider).mine();
});
