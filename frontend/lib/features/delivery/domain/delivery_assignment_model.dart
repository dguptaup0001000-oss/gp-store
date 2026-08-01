import 'package:freezed_annotation/freezed_annotation.dart';

part 'delivery_assignment_model.freezed.dart';
part 'delivery_assignment_model.g.dart';

/// Mirrors backend's MyDeliveryResponse exactly - deliberately excludes
/// order items, pricing, and payment info (see that DTO's doc comment) - a
/// delivery partner needs to know where to go and who to call, not what
/// was bought or how much it cost.
@freezed
class DeliveryAssignment with _$DeliveryAssignment {
  const factory DeliveryAssignment({
    required int deliveryId,
    int? orderId,
    String? orderNumber,
    String? deliveryStatus,
    String? estimatedDeliveryTime,
    String? assignedAt,
    String? customerName,
    String? customerPhone,
    String? deliveryAddress,
    double? latitude,
    double? longitude,
  }) = _DeliveryAssignment;

  factory DeliveryAssignment.fromJson(Map<String, dynamic> json) => _$DeliveryAssignmentFromJson(json);
}
