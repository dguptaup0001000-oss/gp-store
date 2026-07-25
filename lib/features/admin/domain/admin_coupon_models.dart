import 'package:freezed_annotation/freezed_annotation.dart';

import '../../products/domain/product_models.dart' show DiscountType;

part 'admin_coupon_models.freezed.dart';
part 'admin_coupon_models.g.dart';

/// A separate model from the customer-facing Coupon (in products feature) -
/// admin needs usageLimit/usedCount/expiryDate/active, none of which the
/// public /active endpoint's Coupon model exposes or needs.
@freezed
class AdminCoupon with _$AdminCoupon {
  const factory AdminCoupon({
    required int id,
    required String couponCode,
    required DiscountType discountType,
    required double discountValue,
    double? maxDiscountAmount,
    double? minimumOrderAmount,
    String? expiryDate,
    int? usageLimit,
    @Default(0) int usedCount,
    @Default(true) bool active,
  }) = _AdminCoupon;

  factory AdminCoupon.fromJson(Map<String, dynamic> json) => _$AdminCouponFromJson(json);
}
