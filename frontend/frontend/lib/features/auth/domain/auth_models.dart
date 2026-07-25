import 'package:freezed_annotation/freezed_annotation.dart';

part 'auth_models.freezed.dart';
part 'auth_models.g.dart';

/// Mirrors backend's AuthResponse exactly (token, refreshToken, customerId,
/// email, role) - if the backend shape changes, this needs to change too,
/// deliberately, not silently drift.
@freezed
class AuthResponse with _$AuthResponse {
  const factory AuthResponse({
    required String token,
    required String refreshToken,
    required int customerId,
    required String email,
    required String role,
  }) = _AuthResponse;

  factory AuthResponse.fromJson(Map<String, dynamic> json) => _$AuthResponseFromJson(json);
}

/// Mirrors backend's Customer shape for the "my profile" endpoints.
@freezed
class Customer with _$Customer {
  const factory Customer({
    required int id,
    required String fullName,
    required String email,
    required String mobileNumber,
  }) = _Customer;

  factory Customer.fromJson(Map<String, dynamic> json) => _$CustomerFromJson(json);
}
