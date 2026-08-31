import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/util/haptic_widgets.dart';
import '../../features/auth/presentation/auth_providers.dart';
import '../design/admin_components.dart';
import '../design/admin_format.dart';
import '../design/admin_tokens.dart';
import 'store_operations_models.dart';
import 'store_operations_providers.dart';

/// What has to be packed for a delivery run.
///
/// DEFAULTS TO THE NEXT RUN, which overnight is today's 09:00 - so whoever
/// opens this at eight in the morning sees the night's orders without
/// choosing a date. That default is the server's answer, not this app's:
/// deciding it here would mean the phone's clock choosing which day of work
/// to show.
///
/// ONE PAGE AT A TIME. The header shows the day's TOTAL, which is not the
/// length of this list - telling someone there are fifty orders to pack when
/// there are three hundred is worse than telling them nothing.
class MorningPreparationScreen extends ConsumerWidget {
  const MorningPreparationScreen({super.key});

  Future<void> _pickDate(BuildContext context, WidgetRef ref,
      DateTime? current) async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: current ?? now,
      // Backwards as well as forwards: "what did we send out on Tuesday" is a
      // real question, and this list answers it from stored data.
      firstDate: now.subtract(const Duration(days: 90)),
      lastDate: now.add(const Duration(days: 60)),
    );
    if (picked == null) return;
    ref.read(preparationDateProvider.notifier).state = picked;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final preparation = ref.watch(preparationListProvider);
    final selected = ref.watch(preparationDateProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Packing List'),
        actions: [
          IconButton(
            icon: const Icon(Icons.calendar_today_outlined),
            tooltip: 'Choose a day',
            onPressed: hapticize(() => _pickDate(context, ref, selected)),
          ),
          if (selected != null)
            IconButton(
              icon: const Icon(Icons.today_outlined),
              tooltip: 'Back to the next run',
              onPressed: hapticize(
                  () => ref.read(preparationDateProvider.notifier).state = null),
            ),
        ],
      ),
      body: preparation.when(
        loading: () => const AdminListSkeleton(),
        error: (error, _) => AdminErrorState(
          message: "Couldn't load the packing list: ${extractErrorMessage(error)}",
          onRetry: hapticize(() => ref.invalidate(preparationListProvider)),
        ),
        data: (data) {
          if (data.date == null) {
            return AdminEmptyState(
              icon: Icons.event_busy_outlined,
              title: 'No delivery day scheduled',
              message: data.message ??
                  'The shop is marked closed for every day ahead.',
            );
          }
          if (data.orders.isEmpty) {
            return AdminEmptyState(
              icon: Icons.inventory_2_outlined,
              title: 'Nothing to pack for ${AdminFormat.relativeDay(data.date!)}',
              message: 'Orders appear here as soon as customers place them.',
            );
          }

          return RefreshIndicator(
            color: AdminColors.primary,
            onRefresh: () async => ref.invalidate(preparationListProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(AdminSpacing.lg),
              itemCount: data.orders.length + 1,
              separatorBuilder: (_, __) =>
                  const SizedBox(height: AdminSpacing.sm),
              itemBuilder: (context, index) {
                if (index == 0) return _Header(data: data);
                return _OrderRow(order: data.orders[index - 1]);
              },
            ),
          );
        },
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.data});

  final PreparationList data;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AdminSpacing.md),
      child: AdminSectionCard(
        title: AdminFormat.relativeDay(data.date!),
        subtitle: data.packingStartsAt == null
            ? null
            : 'Packing from ${_clock(data.packingStartsAt!)}, '
                'vans from ${_clock(data.deliveriesStartAt ?? '')}',
        child: Row(
          children: [
            Text(
              AdminFormat.count(data.totalOrders),
              style: AdminText.numeric.copyWith(fontSize: 28),
            ),
            const SizedBox(width: AdminSpacing.sm),
            Text(
              data.totalOrders == 1 ? 'order to pack' : 'orders to pack',
              style: AdminText.caption,
            ),
          ],
        ),
      ),
    );
  }

  /// The server sends an instant; this shows the wall-clock time in the
  /// viewer's own zone. Acceptable here and only here: an operator standing
  /// in the shop IS in the shop's timezone, and this is a display of a time
  /// the server already decided, never an input to a decision.
  static String _clock(String iso) {
    final parsed = DateTime.tryParse(iso);
    if (parsed == null) return iso;
    final local = parsed.toLocal();
    final hour = local.hour % 12 == 0 ? 12 : local.hour % 12;
    final suffix = local.hour < 12 ? 'AM' : 'PM';
    return local.minute == 0
        ? '$hour $suffix'
        : '$hour:${local.minute.toString().padLeft(2, '0')} $suffix';
  }
}

class _OrderRow extends StatelessWidget {
  const _OrderRow({required this.order});

  final PreparationOrder order;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AdminSpacing.lg),
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
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  '#${order.orderNumber}',
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    color: AdminColors.textPrimary,
                  ),
                ),
                const SizedBox(height: AdminSpacing.xs),
                Wrap(
                  spacing: AdminSpacing.sm,
                  runSpacing: AdminSpacing.xs,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    AdminStatusBadge(
                      label: AdminStatusBadge.humanizeStatus(order.orderStatus),
                      tone: AdminStatusBadge.toneForOrderStatus(
                          order.orderStatus),
                      dense: true,
                    ),
                    if (order.deliveryType == 'NEXT_MORNING')
                      // Worth calling out: these came in overnight and are the
                      // reason anybody is packing at eight in the morning.
                      const AdminStatusBadge(
                        label: 'Overnight',
                        tone: AdminStatusTone.info,
                        dense: true,
                      ),
                    if (order.paymentStatus != null)
                      Text(
                        AdminStatusBadge.humanizeStatus(order.paymentStatus),
                        style: AdminText.caption,
                      ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(width: AdminSpacing.sm),
          Text(AdminFormat.rupees(order.totalAmount), style: AdminText.numeric),
        ],
      ),
    );
  }
}
