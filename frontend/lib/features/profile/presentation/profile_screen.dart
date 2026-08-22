import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/util/app_haptics.dart';
import '../../address/presentation/address_list_screen.dart';
import '../../admin/presentation/admin_home_screen.dart';
import '../../auth/presentation/auth_providers.dart';
import 'change_password_screen.dart';
import '../../notifications/presentation/notifications_screen.dart';
import '../../notifications/presentation/notifications_providers.dart';
import '../../orders/presentation/order_history_screen.dart';
import '../../reviews/presentation/my_reviews_screen.dart';
import '../../support/presentation/about_screen.dart';
import '../../support/presentation/contact_us_screen.dart';
import '../../support/presentation/privacy_policy_screen.dart';
import '../../support/presentation/terms_screen.dart';
import '../../wishlist/presentation/wishlist_screen.dart';
import 'profile_providers.dart';

class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

  // Set at build time by .github/workflows/build-and-deploy.yml via
  // --dart-define=BUILD_SHA=<short commit sha> - 'dev' for a local run with
  // no dart-define passed. Shown below so it's checkable at a glance
  // whether an installed APK is actually the latest build, rather than
  // guessed at from screenshots - repeatedly the real cause behind bugs
  // reported as still-broken that had already been fixed.
  static const _buildSha = String.fromEnvironment('BUILD_SHA', defaultValue: 'dev');

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profileAsync = ref.watch(myProfileProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Profile')),
      body: profileAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load your profile: ${extractErrorMessage(error)}"),
              TextButton(onPressed: () => ref.invalidate(myProfileProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (profile) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
              child: Row(
                children: [
                  const CircleAvatar(
                    radius: 28,
                    backgroundColor: AppColors.primary,
                    child: Icon(Icons.person, color: Colors.white, size: 28),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(profile.fullName, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                        const SizedBox(height: 2),
                        if (profile.email != null)
                          Text(profile.email!, style: Theme.of(context).textTheme.bodyMedium)
                        else
                          Text(
                            'No email added yet',
                            style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontStyle: FontStyle.italic),
                          ),
                        Text(profile.mobileNumber ?? 'No mobile number', style: Theme.of(context).textTheme.bodyMedium),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.edit_outlined),
                    tooltip: 'Edit profile',
                    onPressed: () =>
                        _showEditDialog(context, ref, profile.fullName, profile.mobileNumber ?? '', profile.email),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            if (profile.role == 'ADMIN')
              _menuTile(
                context,
                icon: Icons.storefront_outlined,
                label: 'Store Management',
                onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const AdminHomeScreen())),
              ),
            _menuTile(
              context,
              icon: Icons.receipt_long_outlined,
              label: 'My Orders',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const OrderHistoryScreen())),
            ),
            _menuTile(
              context,
              icon: Icons.location_on_outlined,
              label: 'My Addresses',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const AddressListScreen())),
            ),
            _menuTile(
              context,
              icon: Icons.favorite_border,
              label: 'My Wishlist',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const WishlistScreen())),
            ),
            _menuTile(
              context,
              icon: Icons.star_border,
              label: 'My Reviews',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const MyReviewsScreen())),
            ),
            _menuTile(
              context,
              icon: Icons.lock_outline,
              label: 'Change Password',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const ChangePasswordScreen())),
            ),
            _menuTile(
              context,
              icon: Icons.notifications_outlined,
              label: 'Notifications',
              badgeCount: ref.watch(unreadNotificationCountProvider).valueOrNull ?? 0,
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotificationsScreen())),
            ),
            const SizedBox(height: 16),
            _menuTile(
              context,
              icon: Icons.support_agent_outlined,
              label: 'Contact Us',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const ContactUsScreen())),
            ),
            _menuTile(
              context,
              icon: Icons.info_outline,
              label: 'About',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const AboutScreen())),
            ),
            _menuTile(
              context,
              icon: Icons.privacy_tip_outlined,
              label: 'Privacy Policy',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PrivacyPolicyScreen())),
            ),
            _menuTile(
              context,
              icon: Icons.description_outlined,
              label: 'Terms of Service',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const TermsScreen())),
            ),
            const SizedBox(height: 16),
            _menuTile(
              context,
              icon: Icons.logout,
              label: 'Log out',
              // Heavy, not selection: this one takes effect on the tap itself,
              // with no confirmation step in between.
              haptic: AppHaptics.heavy,
              onTap: () => ref.read(authControllerProvider.notifier).logout(),
            ),
            _menuTile(
              context,
              icon: Icons.logout,
              label: 'Log out of all devices',
              isDestructive: true,
              onTap: () => _confirmLogoutEverywhere(context, ref),
            ),
            _menuTile(
              context,
              icon: Icons.delete_forever_outlined,
              label: 'Delete Account',
              isDestructive: true,
              onTap: () => _confirmDeleteAccount(context, ref),
            ),
            const SizedBox(height: 24),
            Center(
              child: Text(
                'Build $_buildSha',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(color: AppColors.textSecondary),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Every action on this screen goes through here, which is why the haptic
  /// lives here rather than at each call site.
  ///
  /// ONE PLACE MEANS EXACTLY ONE BUZZ PER TAP. Adding it to each onTap
  /// instead would have been four edits today and a forgotten fifth tomorrow -
  /// and the moment a tile both fires its own haptic and calls something that
  /// fires another, one tap feels like two, which reads as a stuck button
  /// rather than a responsive one.
  ///
  /// [haptic] defaults to a light selection tick because most tiles just
  /// navigate. The destructive ones pass AppHaptics.heavy explicitly - see
  /// their call sites.
  Widget _menuTile(BuildContext context,
      {required IconData icon, required String label, required VoidCallback onTap, bool isDestructive = false, int badgeCount = 0,
       void Function()? haptic}) {
    return ListTile(
      leading: Icon(icon, color: isDestructive ? AppColors.error : AppColors.textPrimary),
      title: Text(label, style: TextStyle(color: isDestructive ? AppColors.error : AppColors.textPrimary)),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (badgeCount > 0)
            Container(
              margin: const EdgeInsets.only(right: 8),
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(color: AppColors.primary, borderRadius: BorderRadius.circular(10)),
              child: Text(
                badgeCount > 99 ? '99+' : '$badgeCount',
                style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w700),
              ),
            ),
          const Icon(Icons.chevron_right, color: AppColors.textSecondary),
        ],
      ),
      onTap: () {
        (haptic ?? AppHaptics.selection)();
        onTap();
      },
    );
  }

  Future<void> _confirmLogoutEverywhere(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Log out everywhere?'),
        content: const Text('This will sign you out on all your devices, not just this one.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          TextButton(
            // Heavy here rather than on the tile that opened this dialog:
            // opening a confirmation does nothing, confirming it signs every
            // other device out. Feedback belongs on the irreversible half.
            onPressed: () {
              AppHaptics.heavy();
              Navigator.of(context).pop(true);
            },
            child: const Text('Log out everywhere'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await ref.read(authControllerProvider.notifier).logoutAllDevices();
    }
  }

  /// Two-step, deliberately - this is the one action in this whole screen
  /// that can't be undone by logging back in. First dialog explains exactly
  /// what does and doesn't happen (matches CustomerService
  /// .deleteOwnAccount's actual behavior on the backend, not a vague
  /// "are you sure"). Second is a typed confirmation, the same friction
  /// pattern used for genuinely irreversible actions elsewhere (e.g.
  /// deleting a payment method on most banking apps) - a single tap is too
  /// easy to hit by accident on a menu this long.
  Future<void> _confirmDeleteAccount(BuildContext context, WidgetRef ref) async {
    final understood = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete your account?'),
        content: const Text(
          'This permanently removes your name, phone number, email, saved '
          'addresses, wishlist, and cart. You will be signed out on every '
          'device immediately and cannot undo this.\n\n'
          "Your past order and invoice history is kept (shown as \"Deleted "
          'User") since Indian tax law requires retaining invoice records '
          "regardless of account deletion - it will no longer be linked to "
          'your name, phone, or email.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Continue', style: TextStyle(color: AppColors.error)),
          ),
        ],
      ),
    );
    if (understood != true || !context.mounted) return;

    final typedController = TextEditingController();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Type DELETE to confirm'),
        content: TextField(
          controller: typedController,
          autofocus: true,
          textCapitalization: TextCapitalization.characters,
          decoration: const InputDecoration(hintText: 'DELETE'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppColors.error),
            onPressed: () {
              final typedCorrectly = typedController.text.trim() == 'DELETE';
              // ONLY when the confirmation actually passes. Buzzing on a
              // rejected tap tells the hand something happened when nothing
              // did, which is precisely the "haptic on a disabled action"
              // case that makes feedback untrustworthy.
              if (typedCorrectly) AppHaptics.heavy();
              Navigator.of(context).pop(typedCorrectly);
            },
            child: const Text('Delete My Account'),
          ),
        ],
      ),
    );
    // The comparison happens inside the dialog action, so nothing needs this
    // after the await. Released rather than left to the garbage collector's
    // discretion - a TextEditingController is a ChangeNotifier.
    typedController.dispose();

    if (confirmed != true) return;

    try {
      await ref.read(profileRepositoryProvider).deleteAccount();
      // The backend has already revoked every refresh token by this point -
      // this just mirrors that locally (clear stored tokens, flip auth
      // state) so the router's listener (see app_router.dart) redirects to
      // login on its own, same as logoutAllDevices() above. logout() is
      // safe to call even though the server-side token is already gone -
      // see AuthRepository.logout()'s doc comment, it clears local storage
      // unconditionally regardless of whether its own server call succeeds.
      await ref.read(authControllerProvider.notifier).logout();
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }

  Future<void> _showEditDialog(
      BuildContext context, WidgetRef ref, String currentName, String currentMobile, String? currentEmail) async {
    final nameController = TextEditingController(text: currentName);
    final mobileController = TextEditingController(text: currentMobile);
    final emailController = TextEditingController(text: currentEmail ?? '');
    final canSetEmail = currentEmail == null;

    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Edit Profile'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: nameController, decoration: const InputDecoration(labelText: 'Full name')),
            const SizedBox(height: 12),
            TextField(controller: mobileController, decoration: const InputDecoration(labelText: 'Mobile number')),
            const SizedBox(height: 12),
            TextField(
              controller: emailController,
              enabled: canSetEmail,
              keyboardType: TextInputType.emailAddress,
              decoration: InputDecoration(
                labelText: 'Email',
                helperText: canSetEmail
                    ? 'Add an email to also enable password login'
                    : "Email can't be changed once set",
                helperMaxLines: 2,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Save')),
        ],
      ),
    );

    // Read first, then release, then use - these three are still needed
    // below, so they cannot be disposed the moment the dialog closes.
    final fullName = nameController.text.trim();
    final mobileNumber = mobileController.text.trim();
    final email = emailController.text.trim();

    nameController.dispose();
    mobileController.dispose();
    emailController.dispose();

    if (saved != true) return;

    try {
      await ref.read(profileRepositoryProvider).updateProfile(
            fullName: fullName,
            mobileNumber: mobileNumber,
            email: canSetEmail && email.isNotEmpty ? email : null,
          );
      ref.invalidate(myProfileProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }
}
