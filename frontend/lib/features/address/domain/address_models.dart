import 'package:freezed_annotation/freezed_annotation.dart';

part 'address_models.freezed.dart';
part 'address_models.g.dart';

@freezed
class AddressModel with _$AddressModel {
  const factory AddressModel({
    int? id,
    required String fullName,
    required String mobileNumber,
    required String houseNo,
    required String area,
    String? landmark,
    required String city,
    String? district,
    required String state,
    required String pincode,
    @Default('India') String country,
    required double latitude,
    required double longitude,
    @Default(false) bool defaultAddress,
  }) = _AddressModel;

  factory AddressModel.fromJson(Map<String, dynamic> json) => _$AddressModelFromJson(json);
}
