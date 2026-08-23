import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/admin_customer_model.dart';
import 'admin_customer_orders_screen.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminCustomersScreen extends ConsumerStatefulWidget {
  const AdminCustomersScreen({super.key});

  @override
  ConsumerState<AdminCustomersScreen> createState() => _AdminCustomersScreenState();
}

class _AdminCustomersScreenState extends ConsumerState<AdminCustomersScreen> {
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<AdminCustomer> _filter(List<AdminCustomer> customers) {
    if (_query.isEmpty) return customers;
    final q = _query.toLowerCase();
    return customers.where((c) {
      return c.fullName.toLowerCase().contains(q) ||
          (c.email?.toLowerCase().contains(q) ?? false) ||
          (c.mobileNumber?.toLowerCase().contains(q) ?? false);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final customersAsync = ref.watch(adminAllCustomersProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Customers')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              controller: _searchController,
              onChanged: (value) => setState(() => _query = value.trim()),
              decoration: const InputDecoration(
                hintText: 'Search by name, email, or mobile',
                prefixIcon: Icon(Icons.search, size: 20),
              ),
            ),
          ),
          Expanded(
            child: customersAsync.when(
              loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
              error: (error, stackTrace) => Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // TEMPORARY, for active debugging - see RootScreen's identical
                    // comment for why this shows the real failure reason instead
                    // of one static string.
                    Text("Couldn't load customers: ${extractErrorMessage(error)}"),
                    TextButton(onPressed: hapticize(() => ref.invalidate(adminAllCustomersProvider)), child: const Text('Retry')),
                  ],
                ),
              ),
              data: (page) {
                final allCustomers = page.customers;
                final customers = _filter(allCustomers);
                final controller = ref.read(adminAllCustomersProvider.notifier);

                // While actively searching, keep pulling in more pages so
                // the search covers the whole customer base, not just
                // whatever page happened to load first - matches the old
                // behavior from before this list was paginated.
                if (_query.isNotEmpty && controller.hasMore) {
                  WidgetsBinding.instance.addPostFrameCallback((_) => controller.loadMore());
                }

                if (customers.isEmpty) {
                  return Center(
                    child: Text(
                      allCustomers.isEmpty ? 'No customers yet' : 'No matches',
                      style: const TextStyle(color: AppColors.textSecondary),
                    ),
                  );
                }

                final hasMore = _query.isEmpty && controller.hasMore;

                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  itemCount: customers.length + (hasMore ? 1 : 0),
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, index) {
                    if (index == customers.length) {
                      WidgetsBinding.instance.addPostFrameCallback((_) => controller.loadMore());
                      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
                    }
                    return _CustomerTile(customer: customers[index]);
                  },
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: hapticize(() async {
          final created = await showDialog<bool>(
            context: context,
            builder: (context) => const _CreateCustomerDialog(),
          );
          if (created == true) ref.invalidate(adminAllCustomersProvider);
        }),
        icon: const Icon(Icons.add),
        label: const Text('Add Customer'),
      ),
    );
  }
}

class _CreateCustomerDialog extends ConsumerStatefulWidget {
  const _CreateCustomerDialog();

  @override
  ConsumerState<_CreateCustomerDialog> createState() => _CreateCustomerDialogState();
}

class _CreateCustomerDialogState extends ConsumerState<_CreateCustomerDialog> {
  final _nameController = TextEditingController();
  final _emailController = TextEditingController();
  final _mobileController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _isSaving = false;

  @override
  void dispose() {
    _nameController.dispose();
    _emailController.dispose();
    _mobileController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_nameController.text.trim().isEmpty || _mobileController.text.trim().isEmpty) return;

    setState(() => _isSaving = true);

    try {
      await ref.read(adminProductsRepositoryProvider).createCustomer(
            fullName: _nameController.text.trim(),
            email: _emailController.text.trim().isEmpty ? null : _emailController.text.trim(),
            mobileNumber: _mobileController.text.trim(),
            password: _passwordController.text.trim().isEmpty ? null : _passwordController.text.trim(),
          );
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text("Couldn't create customer: ${extractErrorMessage(e)}")),
      );
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Add Customer'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: _nameController, decoration: const InputDecoration(labelText: 'Full name')),
            const SizedBox(height: 12),
            TextField(
              controller: _mobileController,
              keyboardType: TextInputType.phone,
              decoration: const InputDecoration(labelText: 'Mobile number'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _emailController,
              keyboardType: TextInputType.emailAddress,
              decoration: const InputDecoration(labelText: 'Email (optional)'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _passwordController,
              obscureText: true,
              decoration: const InputDecoration(
                labelText: 'Password (optional)',
                helperText: "Leave blank if they'll log in via mobile OTP instead",
              ),
              maxLines: 1,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: hapticize(() => Navigator.of(context).pop(false)), child: const Text('Cancel')),
        FilledButton(
          onPressed: _isSaving ? null : _save,
          child: _isSaving
              ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}

class _CustomerTile extends ConsumerStatefulWidget {
  const _CustomerTile({required this.customer});

  final AdminCustomer customer;

  @override
  ConsumerState<_CustomerTile> createState() => _CustomerTileState();
}

class _CustomerTileState extends ConsumerState<_CustomerTile> {
  bool _isUpdating = false;

  Future<void> _toggleActive() async {
    final customer = widget.customer;
    final newValue = !customer.active;

    if (!newValue) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Deactivate this account?'),
          content: const Text(
            'This immediately signs them out of every device and blocks login until reactivated.',
          ),
          actions: [
            TextButton(onPressed: hapticize(() => Navigator.of(context).pop(false)), child: const Text('Cancel')),
            TextButton(onPressed: hapticize(() => Navigator.of(context).pop(true)), child: const Text('Deactivate')),
          ],
        ),
      );
      if (confirmed != true) return;
    }

    setState(() => _isUpdating = true);

    try {
      await ref.read(adminProductsRepositoryProvider).setCustomerActive(
            customerId: customer.id,
            active: newValue,
          );
      ref.invalidate(adminAllCustomersProvider);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isUpdating = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final customer = widget.customer;

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
      child: Row(
        children: [
          Expanded(
            child: InkWell(
              onTap: hapticize(() => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => AdminCustomerOrdersScreen(customerId: customer.id, customerName: customer.fullName),
                ),
              )),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(customer.fullName, style: const TextStyle(fontWeight: FontWeight.w700)),
                      if (customer.role != null && customer.role != 'CUSTOMER') ...[
                        const SizedBox(width: 6),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(color: AppColors.primary.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(4)),
                          child: Text(customer.role!, style: const TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: AppColors.primary)),
                        ),
                      ],
                    ],
                  ),
                  if (customer.email != null) Text(customer.email!, style: Theme.of(context).textTheme.bodyMedium),
                  if (customer.mobileNumber != null) Text(customer.mobileNumber!, style: Theme.of(context).textTheme.bodyMedium),
                  if (!customer.active)
                    const Padding(
                      padding: EdgeInsets.only(top: 4),
                      child: Text('Deactivated', style: TextStyle(color: AppColors.error, fontSize: 11, fontWeight: FontWeight.w600)),
                    ),
                  const Padding(
                    padding: EdgeInsets.only(top: 4),
                    child: Text('Tap to view order history', style: TextStyle(fontSize: 10, color: AppColors.textSecondary)),
                  ),
                ],
              ),
            ),
          ),
          _isUpdating
              ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2))
              : Switch(value: customer.active, onChanged: hapticizeValue((_) => _toggleActive())),
        ],
      ),
    );
  }
}
