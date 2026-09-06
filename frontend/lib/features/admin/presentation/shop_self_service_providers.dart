import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/shop_self_service_repository.dart';
import '../domain/shop_admin_models.dart';

final shopSelfServiceRepositoryProvider =
    Provider<ShopSelfServiceRepository>((ref) {
  return ShopSelfServiceRepository(apiClient: ref.watch(apiClientProvider));
});

/// The shop this staff account is acting for.
///
/// NO ID IS PASSED, ANYWHERE. The backend resolved which shop this is from
/// the credential before the controller ran, which is why there is nothing
/// here for a screen to choose and nothing for this app to have to guard.
final myShopProfileProvider = FutureProvider<ShopProfile>((ref) {
  return ref.watch(shopSelfServiceRepositoryProvider).profile();
});

final myShopReadinessProvider = FutureProvider<ShopReadiness>((ref) {
  return ref.watch(shopSelfServiceRepositoryProvider).readiness();
});

final myShopEarningsProvider =
    FutureProvider.family<ShopEarnings, int>((ref, days) {
  return ref.watch(shopSelfServiceRepositoryProvider).earnings(days: days);
});

final myShopOpenWorkProvider = FutureProvider<Map<String, int>>((ref) {
  return ref.watch(shopSelfServiceRepositoryProvider).openWork();
});
