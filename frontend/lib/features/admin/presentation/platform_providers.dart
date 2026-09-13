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

/// One merchant and its shops.
///
/// autoDispose because this is what one open detail screen is looking at. A
/// platform owner working through a list of merchants would otherwise
/// accumulate a cached answer per merchant they glanced at, and the one that
/// matters - the merchant whose status they just changed - is the one they are
/// about to come back to and want re-read.
final platformMerchantDetailProvider =
    FutureProvider.autoDispose.family<PlatformMerchantDetail, int>((ref, merchantId) {
  return ref.watch(platformRepositoryProvider).merchantDetail(merchantId);
});

final marketOverviewProvider =
    FutureProvider.family<MarketOverview, int>((ref, days) {
  return ref.watch(platformRepositoryProvider).overview(days: days);
});
