import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../domain/admin_coupon_models.dart';
import 'admin_coupon_form_dialog.dart';
import 'admin_providers.dart';

class AdminCouponListScreen extends ConsumerStatefulWidget {
  const AdminCouponListScreen({super.key});

  @override
  ConsumerState<AdminCouponListScreen> createState() => _AdminCouponListScreenState();
}

class _AdminCouponListScreenState extends ConsumerState<AdminCouponListScreen> {
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<AdminCoupon> _filter(List<AdminCoupon> coupons) {
    if (_query.isEmpty) return coupons;
    final q = _query.toLowerCase();
    return coupons.where((c) => c.couponCode.toLowerCase().contains(q)).toList();
  }

  @override
  Widget build(BuildContext context) {
    final couponsAsync = ref.watch(adminAllCouponsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Coupons')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              controller: _searchController,
              onChanged: (value) => setState(() => _query = value.trim()),
              decoration: const InputDecoration(
                hintText: 'Search by coupon code',
                prefixIcon: Icon(Icons.search, size: 20),
              ),
            ),
          ),
          Expanded(
            child: couponsAsync.when(
              loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
              error: (error, stackTrace) => Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text("Couldn't load coupons - check your connection"),
                    TextButton(onPressed: () => ref.invalidate(adminAllCouponsProvider), child: const Text('Retry')),
                  ],
                ),
              ),
              data: (allCoupons) {
                final coupons = _filter(allCoupons);

                if (coupons.isEmpty) {
                  return Center(
                    child: Text(
                      allCoupons.isEmpty ? 'No coupons yet - tap + to add one' : 'No matches',
                      style: const TextStyle(color: AppColors.textSecondary),
                    ),
                  );
                }

                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  itemCount: coupons.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, index) => _CouponTile(coupon: coupons[index]),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          final saved = await showDialog<bool>(
            context: context,
            builder: (context) => const AdminCouponFormDialog(),
          );
          if (saved == true) ref.invalidate(adminAllCouponsProvider);
        },
        icon: const Icon(Icons.add),
        label: const Text('Add Coupon'),
      ),
    );
  }
}

class _CouponTile extends ConsumerWidget {
  const _CouponTile({required this.coupon});

  final AdminCoupon coupon;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final label = coupon.discountType.name == 'percentage'
        ? '${coupon.discountValue.toStringAsFixed(0)}% OFF'
        : '₹${coupon.discountValue.toStringAsFixed(0)} OFF';

    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () async {
        final saved = await showDialog<bool>(
          context: context,
          builder: (context) => AdminCouponFormDialog(coupon: coupon),
        );
        if (saved == true) ref.invalidate(adminAllCouponsProvider);
      },
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(coupon.couponCode, style: const TextStyle(fontWeight: FontWeight.w700)),
                      const SizedBox(width: 8),
                      if (!coupon.active)
                        const Text('Inactive', style: TextStyle(color: AppColors.error, fontSize: 11, fontWeight: FontWeight.w600)),
                    ],
                  ),
                  Text(label, style: Theme.of(context).textTheme.bodyMedium),
                  const SizedBox(height: 4),
                  Text(
                    'Used ${coupon.usedCount}${coupon.usageLimit != null ? ' / ${coupon.usageLimit}' : ''} times'
                    '${coupon.expiryDate != null ? ' · Expires ${coupon.expiryDate}' : ''}',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
