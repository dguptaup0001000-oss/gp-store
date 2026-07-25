import 'package:freezed_annotation/freezed_annotation.dart';

part 'delivery_partner_models.freezed.dart';
part 'delivery_partner_models.g.dart';

@freezed
class DeliveryPartnerModel with _$DeliveryPartnerModel {
  const factory DeliveryPartnerModel({
    int? id,
    required String name,
    required String mobile,
    required String vehicleType,
    String? vehicleNumber,
    @Default(true) bool available,
    @Default(true) bool active,
  }) = _DeliveryPartnerModel;

  factory DeliveryPartnerModel.fromJson(Map<String, dynamic> json) => _$DeliveryPartnerModelFromJson(json);
}
