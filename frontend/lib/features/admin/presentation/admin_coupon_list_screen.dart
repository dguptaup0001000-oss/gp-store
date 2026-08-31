import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart';
import '../domain/admin_coupon_models.dart';
import 'admin_coupon_form_dialog.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

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
              loading: () => const AdminListSkeleton(),
              error: (error, stackTrace) => AdminErrorState(
          // Shows the real failure reason rather than one static
          // string - an admin who can read the cause can act on it.
          message: "Couldn't load coupons: ${extractErrorMessage(error)}",
          onRetry: hapticize(() => ref.invalidate(adminAllCouponsProvider)),
        ),
              data: (allCoupons) {
                final coupons = _filter(allCoupons);

                if (coupons.isEmpty) {
                  return AdminEmptyState(
                    icon: allCoupons.isEmpty
                        ? Icons.local_offer_outlined
                        : Icons.search_off_outlined,
                    title: allCoupons.isEmpty
                        ? 'No coupons yet'
                        : 'No matching coupons',
                    message: allCoupons.isEmpty
                        ? 'Tap the + button to create your first offer.'
                        : 'Try a different code or description.',
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
        onPressed: hapticize(() async {
          final saved = await showDialog<bool>(
            context: context,
            builder: (context) => const AdminCouponFormDialog(),
          );
          if (saved == true) ref.invalidate(adminAllCouponsProvider);
        }),
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
    final label = coupon.discountType.offerLabel(coupon.discountValue);

    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: hapticize(() async {
        final saved = await showDialog<bool>(
          context: context,
          builder: (context) => AdminCouponFormDialog(coupon: coupon),
        );
        if (saved == true) ref.invalidate(adminAllCouponsProvider);
      }),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
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
                        const Text('Inactive', style: TextStyle(color: AdminColors.danger, fontSize: 11, fontWeight: FontWeight.w600)),
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
            const Icon(Icons.chevron_right, color: AdminColors.textSecondary),
          ],
        ),
      ),
    );
  }
}
