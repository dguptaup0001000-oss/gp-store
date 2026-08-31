import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/store/store_status.dart';
import '../../core/store/store_status_copy.dart';
import '../../core/store/store_status_provider.dart';
import '../../core/theme/app_theme.dart';

/// A quiet line telling the customer when their order will arrive.
///
/// SHOWS NOTHING DURING NORMAL HOURS. Between 9 and 9 the answer is "today,
/// as usual", and a banner restating that every time the home screen opens is
/// furniture the eye learns to skip — which means it is also skipped at 20:50,
/// when it finally matters. It appears only when there is something to say:
/// the shop is taking orders for the morning, closing time is near, or orders
/// are paused.
///
/// IT NEVER SAYS THE SHOP IS CLOSED while the shop is taking orders. At 3am
/// this reads "Order now, delivered from 9 AM" — GP STORE is open, it is the
/// van that has stopped.
class StoreStatusBanner extends ConsumerWidget {
  const StoreStatusBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(storeStatusProvider).valueOrNull;
    if (status == null) return const SizedBox.shrink();

    final headline = StoreStatusCopy.banner(status);
    if (headline == null) return const SizedBox.shrink();

    final detail = StoreStatusCopy.bannerDetail(status);
    final paused = !status.acceptingOrders;
    final closing = status.countdownActive;

    // Three tones for three different messages. A paused shop and a closing
    // countdown must not look identical to a routine night-time note - the
    // first two need a customer to change what they are doing.
    final (Color background, Color foreground, IconData icon) = paused
        ? (const Color(0xFFFDECEA), AppColors.error, Icons.pause_circle_outline)
        : closing
            ? (AppColors.cream, AppColors.gold, Icons.timer_outlined)
            : (AppColors.mist, AppColors.primary, Icons.nightlight_round);

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 20, color: foreground),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    headline,
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 13.5,
                      color: foreground,
                    ),
                  ),
                  if (detail != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      detail,
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The same facts, as one line for a checkout or cart screen.
///
/// SEPARATE FROM THE BANNER because the decision is different: on the home
/// screen the customer is browsing and only needs telling when something has
/// changed, but at checkout they are about to commit and the delivery promise
/// should always be visible.
class StoreDeliveryPromise extends ConsumerWidget {
  const StoreDeliveryPromise({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(storeStatusProvider).valueOrNull;
    if (status == null) return const SizedBox.shrink();

    final promise = StoreStatusCopy.deliveryPromise(status);
    // Null when the server has not said. Silence rather than a guessed time:
    // the order confirmation carries the answer the server actually decided.
    if (promise == null) return const SizedBox.shrink();

    final nightly = status.mode == StoreMode.night;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(
          nightly ? Icons.wb_twilight : Icons.local_shipping_outlined,
          size: 16,
          color: AppColors.secondary,
        ),
        const SizedBox(width: 6),
        Flexible(
          child: Text(
            promise,
            style: const TextStyle(
              fontSize: 12.5,
              fontWeight: FontWeight.w600,
              color: AppColors.secondary,
            ),
          ),
        ),
      ],
    );
  }
}
