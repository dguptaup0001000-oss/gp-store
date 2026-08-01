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
    // Nullable, NOT required - this is genuinely critical (same class of
    // bug as Profile.email, but more severe): every OTP-only account
    // (every delivery partner, any customer who registered via OTP) has a
    // real null email here. AuthResponse is parsed at the EXACT MOMENT
    // login/OTP-verify completes - if this were still `required String`,
    // login itself would crash immediately for those accounts, before
    // ever reaching RootScreen or anywhere else in the app.
    String? email,
    required String role,
  }) = _AuthResponse;

  factory AuthResponse.fromJson(Map<String, dynamic> json) => _$AuthResponseFromJson(json);
}
