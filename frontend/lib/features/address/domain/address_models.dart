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

    /// How to find the house, in the customer's own words.
    ///
    /// "hanuman mandir ke piche 2 gali chhod ke green colour ki house hai" is
    /// a better address than any set of form fields, because it is how the
    /// person who lives there would actually direct somebody. House numbers
    /// are decorative in most of the delivery area; a landmark and a turn are
    /// not.
    ///
    /// Maps onto the address's existing deliveryInstructions, which already
    /// travels onto the order snapshot and out to the worker's screen under
    /// its own heading - so this needed a field on the form, not a new column.
    String? deliveryInstructions,

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
