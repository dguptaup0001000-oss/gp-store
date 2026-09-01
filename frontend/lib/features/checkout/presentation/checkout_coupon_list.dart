import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../products/domain/product_models.dart';
import '../../products/presentation/products_providers.dart';

/// Every offer the shop is running, on the checkout screen, each with its own
/// Apply button.
///
/// WHY THIS EXISTS. Checkout used to show one empty text field. That is only
/// usable by a shopper who already knows a code, which means the offers were
/// effectively invisible at the one moment they change a decision - and a
/// shopper who half-remembers "there was something about ₹50 off" ends up
/// typing guesses and getting errors. Listing them turns the field from a
/// memory test into a menu.
///
/// The codes are still typeable: the field above stays, because a code from
/// a WhatsApp forward or a printed flyer will not be in this list.
///
/// Nothing here decides whether a coupon is valid for THIS basket. Tapping
/// Apply fills the code in and re-runs the server-side preview, which is the
/// only thing that can price an order - a coupon whose minimum order this
/// basket does not meet comes back as a rejection from the backend, exactly
/// as a typed code would. The conditions listed under each offer are there to
/// explain that rejection before it happens, not to pre-empt it locally.
class CheckoutCouponList extends ConsumerWidget {
  const CheckoutCouponList({
    super.key,
    required this.codeField,
    required this.onApply,
    required this.onRemove,
  });

  /// The checkout screen's own coupon field, watched rather than read once.
  ///
  /// A plain String here went stale the moment the shopper TYPED a code
  /// instead of tapping Apply: a TextField edit does not rebuild the checkout
  /// screen, so the tile for the code they had just entered by hand kept
  /// offering "Apply" as though nothing were applied. Listening to the
  /// controller is what keeps the highlight honest for both routes in.
  final TextEditingController codeField;
  final ValueChanged<String> onApply;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final offers = ref.watch(activeOffersProvider);

    return offers.when(
      // A shop with no offers running shows nothing at all rather than an
      // empty-state card - "no coupons available" is a sentence that makes
      // checkout feel poorer, and the shopper lost nothing by not seeing it.
      data: (list) => list.isEmpty
          ? const SizedBox.shrink()
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SizedBox(height: 12),
                Text(
                  'Available offers',
                  style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                ),
                const SizedBox(height: 8),
                ValueListenableBuilder<TextEditingValue>(
                  valueListenable: codeField,
                  builder: (context, value, _) {
                    final typed = value.text.trim().toUpperCase();
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        for (final offer in list)
                          _CouponTile(
                            offer: offer,
                            applied:
                                typed == offer.couponCode.toUpperCase(),
                            onApply: () => onApply(offer.couponCode),
                            onRemove: onRemove,
                          ),
                      ],
                    );
                  },
                ),
              ],
            ),
      // Offers are a bonus, not a blocker. If this call fails the customer
      // can still type a code and still check out, so a failed load is
      // silent rather than an error banner over the payment screen.
      error: (_, __) => const SizedBox.shrink(),
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(vertical: 12),
        child: Center(
          child: SizedBox(
            height: 18,
            width: 18,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
      ),
    );
  }
}

class _CouponTile extends StatelessWidget {
  const _CouponTile({
    required this.offer,
    required this.applied,
    required this.onApply,
    required this.onRemove,
  });

  final Coupon offer;
  final bool applied;
  final VoidCallback onApply;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final conditions = offer.conditions;

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(12),
        // The applied coupon is outlined rather than merely labelled, so the
        // one that is actually working on this order is findable at a glance
        // in a list of eight.
        border: Border.all(
          color: applied
              ? AppColors.cart
              : AppColors.textSecondary.withValues(alpha: 0.18),
          width: applied ? 1.5 : 1,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      offer.headline,
                      style: const TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 15,
                      ),
                    ),
                    const SizedBox(height: 4),
                    // The code is selectable AND copyable: some shoppers will
                    // want to send it to whoever is paying.
                    GestureDetector(
                      onTap: hapticize(() async {
                        await Clipboard.setData(
                            ClipboardData(text: offer.couponCode));
                        if (!context.mounted) return;
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                            content:
                                Text('Code "${offer.couponCode}" copied'),
                            duration: const Duration(seconds: 2),
                          ),
                        );
                      }),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            'Use code ${offer.couponCode}',
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.textSecondary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(width: 4),
                          const Icon(Icons.copy_rounded,
                              size: 12, color: AppColors.textSecondary),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              // One button that toggles. A separate "Remove" elsewhere would
              // leave the shopper hunting for how to undo what they just did.
              applied
                  ? OutlinedButton(
                      onPressed: hapticize(onRemove),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppColors.cart,
                        side: const BorderSide(color: AppColors.cart),
                        minimumSize: const Size(0, 36),
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                      ),
                      child: const Text('Applied'),
                    )
                  : FilledButton(
                      onPressed: hapticize(onApply),
                      style: FilledButton.styleFrom(
                        minimumSize: const Size(0, 36),
                        padding: const EdgeInsets.symmetric(horizontal: 18),
                      ),
                      child: const Text('Apply'),
                    ),
            ],
          ),
          if (conditions.isNotEmpty) ...[
            const SizedBox(height: 8),
            const Divider(height: 1),
            const SizedBox(height: 8),
            for (final line in conditions)
              Padding(
                padding: const EdgeInsets.only(bottom: 3),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Padding(
                      padding: EdgeInsets.only(top: 5, right: 6),
                      child: SizedBox(
                        height: 4,
                        width: 4,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: AppColors.textSecondary,
                            shape: BoxShape.circle,
                          ),
                        ),
                      ),
                    ),
                    Expanded(
                      child: Text(
                        line,
                        style: const TextStyle(
                          fontSize: 11.5,
                          color: AppColors.textSecondary,
                          height: 1.35,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ],
      ),
    );
  }
}
