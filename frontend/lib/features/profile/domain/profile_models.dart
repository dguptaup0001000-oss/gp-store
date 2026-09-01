import 'package:freezed_annotation/freezed_annotation.dart';

part 'profile_models.freezed.dart';
part 'profile_models.g.dart';

/// Mirrors backend's Customer /me response. Deliberately does NOT include
/// role/enabled/active/cart/addresses even though the raw entity technically
/// has them - a profile screen only needs identity fields, and modeling only
/// what's actually used avoids the app silently depending on fields the
/// backend's own updateOwnProfile() doesn't let you change anyway (email,
/// password, role are intentionally not editable via /me - see CustomerService).
@freezed
class Profile with _$Profile {
  const factory Profile({
    required int id,
    required String fullName,
    // Nullable, NOT required - this is genuinely critical: every OTP-only
    // account (every delivery partner, any customer who registered via OTP
    // and never set an email) has a real null here. Every logged-in user
    // passes through RootScreen, which parses this exact model to decide
    // customer-vs-delivery-partner routing - if this were still
    // `required String`, EVERY delivery partner and OTP-only customer would
    // fail to parse their own profile and crash immediately on login.
    String? email,
    // Nullable for the same reason as email above: deleteOwnAccount()
    // (Google Play account-deletion) nulls this out server-side but only
    // revokes refresh tokens, not the already-issued access token (JWTs are
    // stateless - see jwt.expiration-ms). For up to that token's remaining
    // lifetime, GET /me can legitimately return a null mobileNumber - if
    // this were still `required String`, that window would crash profile
    // parsing instead of just showing an account that's mid-deletion.
    String? mobileNumber,
    // Needed to gate the admin entry point - deliberately NOT sourced from
    // AuthState.user.role, which is only populated right after a fresh
    // login/OTP-verify and stays null after an app restart (session restore
    // only checks for a stored token, it doesn't refetch identity). This
    // field, by contrast, is freshly fetched every time this screen loads.
    @Default('CUSTOMER') String role,

    /// A signed URL for the customer's avatar, or null when they have not set
    /// one - which is most accounts.
    ///
    /// SHORT-LIVED, AND MUST NOT BE CACHED TO DISK. The bucket is private, so
    /// the server mints a fresh signed URL on every /me response. Persisting
    /// this string anywhere would produce a broken image within the hour;
    /// re-fetching the profile is what refreshes it.
    String? profileImageUrl,
  }) = _Profile;

  factory Profile.fromJson(Map<String, dynamic> json) => _$ProfileFromJson(json);
}
