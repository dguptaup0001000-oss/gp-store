import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../domain/address_models.dart';
import 'add_address_screen.dart';
import 'address_providers.dart';

/// Used both as a standalone "manage my addresses" screen and, when
/// [selectMode] is true, as a picker that pops the selected address back to
/// the caller (checkout) via Navigator.pop(address).
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
              const Text("Couldn't load your addresses - check your connection"),
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
            itemBuilder: (context, index) {
              final address = addresses[index];
              return InkWell(
                onTap: selectMode ? () => Navigator.of(context).pop(address) : null,
                borderRadius: BorderRadius.circular(12),
                child: Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: AppColors.cardBackground,
                    borderRadius: BorderRadius.circular(12),
                  ),
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
                    ],
                  ),
                ),
              );
            },
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
