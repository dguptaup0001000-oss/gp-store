import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/platform_repository.dart';
import '../domain/platform_models.dart';

final platformRepositoryProvider = Provider<PlatformRepository>((ref) {
  return PlatformRepository(apiClient: ref.watch(apiClientProvider));
});

final platformMerchantsProvider = FutureProvider<List<MerchantView>>((ref) {
  return ref.watch(platformRepositoryProvider).merchants();
});

final platformShopsProvider = FutureProvider<List<PlatformShopView>>((ref) {
  return ref.watch(platformRepositoryProvider).shops();
});

final marketOverviewProvider =
    FutureProvider.family<MarketOverview, int>((ref, days) {
  return ref.watch(platformRepositoryProvider).overview(days: days);
});
