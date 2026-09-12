import 'package:freezed_annotation/freezed_annotation.dart';

part 'auth_models.freezed.dart';
part 'auth_models.g.dart';

/// Mirrors backend's AuthResponse exactly (token, refreshToken, customerId,
/// email, role, mustChangePassword) - if the backend shape changes, this needs to change too,
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
    // A CLIENT CANNOT ASK, SO IT IS TOLD. While this is true the backend's
    // JwtFilter refuses every route but /api/auth/change-password -
    // /api/customers/me included - so there is no second call that could
    // discover it. Login and refresh are the only responses allowed through,
    // which is why it travels here.
    //
    // THIS APP DELIBERATELY DOES NOT BRANCH ON IT. SignedInHome acts on the
    // 403 the refused profile call returns, because that signal cannot go
    // stale: a flag cached from login would still read true after the
    // password was changed, and would strand the merchant on the change
    // screen for the rest of the session. It also catches a password the
    // platform RESET mid-session, which no login response can. The field is
    // here because this class mirrors the server's shape (see above) and
    // because it is what any other client - a web console, an integration -
    // would have to read.
    //
    // Defaulted, not required: an older backend that does not send the field
    // must still log people in.
    @Default(false) bool mustChangePassword,
  }) = _AuthResponse;

  factory AuthResponse.fromJson(Map<String, dynamic> json) => _$AuthResponseFromJson(json);
}
