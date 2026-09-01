import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/images/gp_network_image.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/profile_models.dart';
import 'profile_providers.dart';

/// The customer's picture, and the tap target that changes it.
///
/// FALLS BACK TO AN INITIAL, NOT A GREY SILHOUETTE. Almost every account has
/// no photo, so the no-photo state is the common one and deserves to look
/// deliberate. A letter on the brand colour reads as "this is you"; the
/// generic person icon reads as "something failed to load".
class ProfileAvatar extends ConsumerStatefulWidget {
  const ProfileAvatar({super.key, required this.profile, this.radius = 28});

  final Profile profile;
  final double radius;

  @override
  ConsumerState<ProfileAvatar> createState() => _ProfileAvatarState();
}

class _ProfileAvatarState extends ConsumerState<ProfileAvatar> {
  bool _busy = false;

  Future<void> _run(Future<Profile?> Function() action, String doneMessage) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final updated = await action();
      if (!mounted) return;
      // null means the customer backed out of the picker. That is not a
      // failure and must not be announced as one.
      if (updated == null) return;

      // The /me response is the source of truth for the new signed URL, so
      // the whole provider is refreshed rather than the widget patching its
      // own copy - otherwise every other screen showing the avatar keeps the
      // old one until something else happens to reload it.
      ref.invalidate(myProfileProvider);
      messenger(context).showSnackBar(SnackBar(content: Text(doneMessage)));
    } catch (e) {
      if (!mounted) return;
      messenger(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  ScaffoldMessengerState messenger(BuildContext context) =>
      ScaffoldMessenger.of(context);

  Future<void> _showOptions() async {
    final hasPhoto = widget.profile.profileImageUrl != null;

    final choice = await showModalBottomSheet<String>(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.photo_camera_outlined),
              title: const Text('Take a photo'),
              onTap: () => Navigator.pop(sheetContext, 'camera'),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('Choose from gallery'),
              onTap: () => Navigator.pop(sheetContext, 'gallery'),
            ),
            if (hasPhoto)
              ListTile(
                leading: const Icon(Icons.delete_outline, color: AppColors.error),
                title: const Text('Remove photo',
                    style: TextStyle(color: AppColors.error)),
                onTap: () => Navigator.pop(sheetContext, 'remove'),
              ),
          ],
        ),
      ),
    );
    if (choice == null || !mounted) return;

    final repository = ref.read(profileRepositoryProvider);
    switch (choice) {
      case 'camera':
        await _run(
            () => repository.pickAndSetProfilePhoto(source: ImageSource.camera),
            'Profile photo updated');
      case 'gallery':
        await _run(
            () => repository.pickAndSetProfilePhoto(),
            'Profile photo updated');
      case 'remove':
        await _run(repository.removeProfilePhoto, 'Profile photo removed');
    }
  }

  @override
  Widget build(BuildContext context) {
    final url = widget.profile.profileImageUrl;
    final diameter = widget.radius * 2;

    return Semantics(
      button: true,
      label: url == null ? 'Add a profile photo' : 'Change your profile photo',
      child: GestureDetector(
        onTap: hapticize(_showOptions),
        child: SizedBox(
          width: diameter,
          height: diameter,
          child: Stack(
            children: [
              ClipOval(
                child: SizedBox(
                  width: diameter,
                  height: diameter,
                  child: url == null || url.isEmpty
                      ? _InitialAvatar(
                          name: widget.profile.fullName, radius: widget.radius)
                      // cover, not the contain default: a portrait must fill
                      // the circle. contain is right for packet shots, where
                      // cropping destroys the thing being identified, and
                      // wrong for a face, where letterboxing leaves grey bars
                      // inside a round frame.
                      : GpNetworkImage.fill(
                          url: url,
                          fit: BoxFit.cover,
                          fallbackIcon: Icons.person,
                        ),
                ),
              ),
              if (_busy)
                Positioned.fill(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.45),
                      shape: BoxShape.circle,
                    ),
                    child: const Center(
                      child: SizedBox(
                        height: 18,
                        width: 18,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: Colors.white),
                      ),
                    ),
                  ),
                )
              else
                // A small camera badge, because a circular photo does not
                // otherwise look like something you can tap.
                Positioned(
                  right: 0,
                  bottom: 0,
                  child: Container(
                    padding: const EdgeInsets.all(4),
                    decoration: BoxDecoration(
                      color: AppColors.primary,
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white, width: 1.5),
                    ),
                    child: const Icon(Icons.photo_camera,
                        size: 11, color: Colors.white),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _InitialAvatar extends StatelessWidget {
  const _InitialAvatar({required this.name, required this.radius});

  final String name;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final trimmed = name.trim();
    // A name can genuinely be empty on an OTP-only account that never set
    // one, so this cannot index blindly into the string.
    final initial =
        trimmed.isEmpty ? '?' : trimmed.characters.first.toUpperCase();

    return Container(
      color: AppColors.primary,
      alignment: Alignment.center,
      child: Text(
        initial,
        style: TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.w700,
          fontSize: radius * 0.9,
        ),
      ),
    );
  }
}
