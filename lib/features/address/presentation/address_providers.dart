import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/address_repository.dart';
import '../domain/address_models.dart';

final addressRepositoryProvider = Provider<AddressRepository>((ref) {
  return AddressRepository(apiClient: ref.watch(apiClientProvider));
});

final myAddressesProvider = FutureProvider<List<AddressModel>>((ref) {
  return ref.watch(addressRepositoryProvider).getMyAddresses();
});

/// checkDeliverable existed on the repository already but was never
/// actually called from anywhere - a customer could only discover whether
/// an address was deliverable after reaching checkout. This surfaces it
/// directly on the address list instead.
final addressDeliverableProvider =
    FutureProvider.family<({bool deliverable, double? distanceKm}), int>((ref, addressId) {
  return ref.watch(addressRepositoryProvider).checkDeliverable(addressId);
});
