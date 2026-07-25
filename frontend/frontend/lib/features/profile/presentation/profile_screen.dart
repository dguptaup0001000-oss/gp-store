import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../address/presentation/address_list_screen.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../notifications/presentation/notifications_screen.dart';
import '../../orders/presentation/order_history_screen.dart';
import 'profile_providers.dart';

class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

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
              const Text("Couldn't load your profile - check your connection"),
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
                        Text(profile.email, style: Theme.of(context).textTheme.bodyMedium),
                        Text(profile.mobileNumber, style: Theme.of(context).textTheme.bodyMedium),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.edit_outlined),
                    onPressed: () => _showEditDialog(context, ref, profile.fullName, profile.mobileNumber),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
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
              icon: Icons.notifications_outlined,
              label: 'Notifications',
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotificationsScreen())),
            ),
            const SizedBox(height: 16),
            _menuTile(
              context,
              icon: Icons.logout,
              label: 'Log out',
              onTap: () => ref.read(authControllerProvider.notifier).logout(),
            ),
            _menuTile(
              context,
              icon: Icons.logout,
              label: 'Log out of all devices',
              isDestructive: true,
              onTap: () => _confirmLogoutEverywhere(context, ref),
            ),
          ],
        ),
      ),
    );
  }

  Widget _menuTile(BuildContext context,
      {required IconData icon, required String label, required VoidCallback onTap, bool isDestructive = false}) {
    return ListTile(
      leading: Icon(icon, color: isDestructive ? AppColors.error : AppColors.textPrimary),
      title: Text(label, style: TextStyle(color: isDestructive ? AppColors.error : AppColors.textPrimary)),
      trailing: const Icon(Icons.chevron_right, color: AppColors.textSecondary),
      onTap: onTap,
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
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Log out everywhere')),
        ],
      ),
    );
    if (confirmed == true) {
      await ref.read(authControllerProvider.notifier).logoutAllDevices();
    }
  }

  Future<void> _showEditDialog(BuildContext context, WidgetRef ref, String currentName, String currentMobile) async {
    final nameController = TextEditingController(text: currentName);
    final mobileController = TextEditingController(text: currentMobile);

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
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Save')),
        ],
      ),
    );

    if (saved != true) return;

    try {
      await ref.read(profileRepositoryProvider).updateProfile(
            fullName: nameController.text.trim(),
            mobileNumber: mobileController.text.trim(),
          );
      ref.invalidate(myProfileProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Couldn't update profile - please try again")),
      );
    }
  }
}
