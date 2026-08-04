/// Mirrors backend's DeliveryTrackingResponse - the assigned delivery
/// partner's live GPS position for a customer's own order. Polled
/// periodically while the order is OUT_FOR_DELIVERY (see order_detail_screen).
///
/// Deliberately a plain hand-written class, not freezed - this app mixes
/// both styles (see Invoice), and a plain class here means no build_runner
/// step is needed to pick this up.
class LiveDeliveryLocation {
  const LiveDeliveryLocation({
    required this.deliveryId,
    this.deliveryStatus,
    this.estimatedDeliveryTime,
    this.partnerLatitude,
    this.partnerLongitude,
    this.locationUpdatedAt,
  });

  final int deliveryId;
  final String? deliveryStatus;
  final String? estimatedDeliveryTime;

  // Null just means the partner's app hasn't pushed a position yet (e.g.
  // order still being prepared, or they haven't opened their app) - not an
  // error, don't treat it as one.
  final double? partnerLatitude;
  final double? partnerLongitude;
  final String? locationUpdatedAt;

  bool get hasLocation => partnerLatitude != null && partnerLongitude != null;

  factory LiveDeliveryLocation.fromJson(Map<String, dynamic> json) {
    return LiveDeliveryLocation(
      deliveryId: json['deliveryId'] as int,
      deliveryStatus: json['deliveryStatus'] as String?,
      estimatedDeliveryTime: json['estimatedDeliveryTime'] as String?,
      partnerLatitude: (json['partnerLatitude'] as num?)?.toDouble(),
      partnerLongitude: (json['partnerLongitude'] as num?)?.toDouble(),
      locationUpdatedAt: json['locationUpdatedAt'] as String?,
    );
  }
}
