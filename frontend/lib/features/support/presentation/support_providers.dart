import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/store_info_repository.dart';
import '../domain/store_info_model.dart';

final storeInfoRepositoryProvider = Provider<StoreInfoRepository>((ref) {
  return StoreInfoRepository(apiClient: ref.watch(apiClientProvider));
});

final storeInfoProvider = FutureProvider<StoreInfo>((ref) {
  return ref.watch(storeInfoRepositoryProvider).getStoreInfo();
});
