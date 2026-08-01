import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../../admin/domain/delivery_partner_models.dart';
import '../data/delivery_partner_repository.dart';
import '../domain/delivery_assignment_model.dart';

final deliveryPartnerRepositoryProvider = Provider<DeliveryPartnerRepository>((ref) {
  return DeliveryPartnerRepository(apiClient: ref.watch(apiClientProvider));
});

final myAssignmentsProvider = FutureProvider<List<DeliveryAssignment>>((ref) {
  return ref.watch(deliveryPartnerRepositoryProvider).getMyAssignments();
});

final myDeliveryProfileProvider = FutureProvider<DeliveryPartnerModel>((ref) {
  return ref.watch(deliveryPartnerRepositoryProvider).getMyProfile();
});
