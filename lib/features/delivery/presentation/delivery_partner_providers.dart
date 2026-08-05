import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../../admin/domain/delivery_partner_models.dart';
import '../data/delivery_partner_repository.dart';
import '../domain/delivery_assignment_model.dart';

final deliveryPartnerRepositoryProvider = Provider<DeliveryPartnerRepository>((ref) {
  return DeliveryPartnerRepository(apiClient: ref.watch(apiClientProvider));
});

final myAssignmentsProvider = FutureProvider<List<DeliveryAssignment>>((ref) {
  // See myProfileProvider's comment (profile_providers.dart) for why this
  // watch is required - without it, a different account logging in on the
  // same device without a full app restart would keep seeing the PREVIOUS
  // delivery partner's assignments.
  ref.watch(authControllerProvider);
  return ref.watch(deliveryPartnerRepositoryProvider).getMyAssignments();
});

final myDeliveryProfileProvider = FutureProvider<DeliveryPartnerModel>((ref) {
  ref.watch(authControllerProvider);
  return ref.watch(deliveryPartnerRepositoryProvider).getMyProfile();
});
