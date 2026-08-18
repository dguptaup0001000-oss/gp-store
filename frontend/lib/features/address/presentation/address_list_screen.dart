import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/address_models.dart';
import 'add_address_screen.dart';
import 'address_providers.dart';

/// Used both as a standalone "manage my addresses" screen and, when
/// [selectMode] is true, as a picker that pops the selected address back to
/// the caller (checkout) via Navigator.pop(address). Edit/delete only show
/// in the standalone mode - mid-checkout isn't the moment to let someone
/// start editing an address instead of picking one.
class AddressListScreen extends ConsumerWidget {
  const AddressListScreen({super.key, this.selectMode = false});

  final bool selectMode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final addressesAsync = ref.watch(myAddressesProvider);

    return Scaffold(
      appBar: AppBar(title: Text(selectMode ? 'Select delivery address' : 'My Addresses')),
      body: addressesAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load your addresses: ${extractErrorMessage(error)}"),
              TextButton(onPressed: () => ref.invalidate(myAddressesProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (addresses) {
          if (addresses.isEmpty) {
            return const Center(
              child: Text('No saved addresses yet', style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: addresses.length,
            separatorBuilder: (_, __) => const SizedBox(height: 12),
            itemBuilder: (context, index) => _AddressTile(address: addresses[index], selectMode: selectMode),
          );
        },
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          final added = await Navigator.of(context).push<bool>(
            MaterialPageRoute(builder: (_) => const AddAddressScreen()),
          );
          if (added == true) {
            ref.invalidate(myAddressesProvider);
          }
        },
        icon: const Icon(Icons.add),
        label: const Text('Add Address'),
      ),
    );
  }
}

class _DeliverabilityBadge extends ConsumerWidget {
  const _DeliverabilityBadge({required this.addressId});

  final int addressId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final deliverableAsync = ref.watch(addressDeliverableProvider(addressId));

    return deliverableAsync.when(
      loading: () => const SizedBox(
        height: 14,
        width: 14,
        child: CircularProgressIndicator(strokeWidth: 2),
      ),
      // Silent on error - a failed check shouldn't clutter the address list
      // with an alarming message; the real check still happens at checkout.
      error: (error, stackTrace) => const SizedBox.shrink(),
      data: (result) {
        if (result.deliverable) {
          return const Row(
            children: [
              Icon(Icons.check_circle, size: 14, color: AppColors.success),
              SizedBox(width: 4),
              Text('We deliver here', style: TextStyle(fontSize: 11, color: AppColors.success, fontWeight: FontWeight.w600)),
            ],
          );
        }
        return const Row(
          children: [
            Icon(Icons.cancel_outlined, size: 14, color: AppColors.error),
            SizedBox(width: 4),
            Text('Outside delivery range', style: TextStyle(fontSize: 11, color: AppColors.error, fontWeight: FontWeight.w600)),
          ],
        );
      },
    );
  }
}

class _AddressTile extends ConsumerWidget {
  const _AddressTile({required this.address, required this.selectMode});

  final AddressModel address;
  final bool selectMode;

  Future<void> _edit(BuildContext context, WidgetRef ref) async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => AddAddressScreen(existingAddress: address)),
    );
    if (saved == true) ref.invalidate(myAddressesProvider);
  }

  Future<void> _delete(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete this address?'),
        content: const Text('This cannot be undone.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Delete')),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await ref.read(addressRepositoryProvider).deleteAddress(address.id!);
      ref.invalidate(myAddressesProvider);
    } catch (e) {
      if (!context.mounted) return;
      // Surfaces the real backend reason (e.g. "used in a past order and
      // can't be deleted") rather than a generic failure message.
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return InkWell(
      onTap: selectMode ? () => Navigator.of(context).pop(address) : null,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.cardBackground,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(address.fullName, style: const TextStyle(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 4),
                  Text(
                    '${address.houseNo}, ${address.area}${address.landmark != null ? ', ${address.landmark}' : ''}, ${address.city}, ${address.state} - ${address.pincode}',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 4),
                  Text(address.mobileNumber, style: Theme.of(context).textTheme.bodyMedium),
                  if (address.id != null) ...[
                    const SizedBox(height: 6),
                    _DeliverabilityBadge(addressId: address.id!),
                  ],
                ],
              ),
            ),
            if (!selectMode)
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_vert, size: 20),
                onSelected: (value) {
                  if (value == 'edit') _edit(context, ref);
                  if (value == 'delete') _delete(context, ref);
                },
                itemBuilder: (context) => const [
                  PopupMenuItem(value: 'edit', child: Text('Edit')),
                  PopupMenuItem(value: 'delete', child: Text('Delete')),
                ],
              ),
          ],
        ),
      ),
    );
  }
}
