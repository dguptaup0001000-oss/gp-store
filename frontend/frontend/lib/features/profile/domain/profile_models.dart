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
    required String email,
    required String mobileNumber,
  }) = _Profile;

  factory Profile.fromJson(Map<String, dynamic> json) => _$ProfileFromJson(json);
}
