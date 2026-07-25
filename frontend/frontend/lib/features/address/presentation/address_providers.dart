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
