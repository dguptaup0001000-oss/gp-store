/// GET /api/deliveries/breached now returns DeliveryResponse - a flat DTO
/// with orderId/orderNumber promoted to top-level fields instead of a
/// nested raw Order entity. This mirrors that shape directly.
class DeliveryBreach {
  const DeliveryBreach({
    required this.id,
    this.orderId,
    this.orderNumber,
    this.deliveryStatus,
    this.estimatedDeliveryTime,
    this.deliveredAt,
    this.deliveryPersonName,
    this.assignedAt,
  });

  final int id;
  final int? orderId;
  final String? orderNumber;
  final String? deliveryStatus;
  final String? estimatedDeliveryTime;
  final String? deliveredAt;
  final String? deliveryPersonName;
  final String? assignedAt;

  factory DeliveryBreach.fromJson(Map<String, dynamic> json) {
    return DeliveryBreach(
      id: json['deliveryId'] as int,
      orderId: json['orderId'] as int?,
      orderNumber: json['orderNumber'] as String?,
      deliveryStatus: json['deliveryStatus'] as String?,
      estimatedDeliveryTime: json['estimatedDeliveryTime'] as String?,
      deliveredAt: json['deliveredAt'] as String?,
      deliveryPersonName: json['deliveryPersonName'] as String?,
      assignedAt: json['assignedAt'] as String?,
    );
  }
}
