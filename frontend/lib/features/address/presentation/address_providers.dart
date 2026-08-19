import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/address_repository.dart';
import '../domain/address_models.dart';

final addressRepositoryProvider = Provider<AddressRepository>((ref) {
  return AddressRepository(apiClient: ref.watch(apiClientProvider));
});

// autoDispose - the address list is a drill-down screen (Profile > My
// Addresses), not persistent app state, so there's no reason to keep it (or
// its per-address deliverability checks below) cached once the customer
// navigates away.
final myAddressesProvider = FutureProvider.autoDispose<List<AddressModel>>((ref) {
  return ref.watch(addressRepositoryProvider).getMyAddresses();
});

/// checkDeliverable existed on the repository already but was never
/// actually called from anywhere - a customer could only discover whether
/// an address was deliverable after reaching checkout. This surfaces it
/// directly on the address list instead.
final addressDeliverableProvider =
    FutureProvider.autoDispose.family<({bool deliverable, double? distanceKm}), int>((ref, addressId) {
  return ref.watch(addressRepositoryProvider).checkDeliverable(addressId);
});
