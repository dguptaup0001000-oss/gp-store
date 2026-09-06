import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../core/api/error_messages.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/shop_admin_models.dart';
import 'shop_self_service_providers.dart';

/// What this shop took, and in what form it arrived.
///
/// THERE IS NO COMMISSION LINE, and its absence is a decision rather than an
/// omission. Under W1 each merchant collects directly, so there is nothing
/// for the platform to settle; and the commission model itself (W2) has not
/// been decided. A zero-valued platform fee shown beside real money would
/// read as a decision that has been made, and a shopkeeper would plan
/// against it.
///
/// EVERY NUMBER IS THE SERVER'S. /api/shop/earnings computes them for the
/// caller's own shop - there is no shop id in the request - and nothing here
/// adds, nets or re-derives anything.
class ShopEarningsScreen extends ConsumerStatefulWidget {
  const ShopEarningsScreen({super.key});

  @override
  ConsumerState<ShopEarningsScreen> createState() => _ShopEarningsScreenState();
}

class _ShopEarningsScreenState extends ConsumerState<ShopEarningsScreen> {
  int _days = 30;

  @override
  Widget build(BuildContext context) {
    final earningsAsync = ref.watch(myShopEarningsProvider(_days));

    return Scaffold(
      appBar: AppBar(title: const Text('Earnings')),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(myShopEarningsProvider(_days)),
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            SegmentedButton<int>(
              segments: const [
                ButtonSegment(value: 7, label: Text('7 days')),
                ButtonSegment(value: 30, label: Text('30 days')),
                ButtonSegment(value: 90, label: Text('90 days')),
              ],
              selected: {_days},
              onSelectionChanged: (selection) =>
                  setState(() => _days = selection.first),
            ),
            const SizedBox(height: 16),
            earningsAsync.when(
              loading: () => const Padding(
                padding: EdgeInsets.all(32),
                child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
              ),
              error: (error, _) => Column(
                children: [
                  Text("Couldn't load earnings: ${extractErrorMessage(error)}"),
                  const SizedBox(height: 8),
                  TextButton(
                    onPressed: hapticize(
                        () => ref.invalidate(myShopEarningsProvider(_days))),
                    child: const Text('Retry'),
                  ),
                ],
              ),
              data: (earnings) => _Figures(earnings: earnings),
            ),
          ],
        ),
      ),
    );
  }
}

class _Figures extends StatelessWidget {
  const _Figures({required this.earnings});

  final ShopEarnings earnings;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (earnings.from != null && earnings.to != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Text('${earnings.from} to ${earnings.to}',
                style: Theme.of(context).textTheme.bodySmall),
          ),
        AdminSectionCard(
          title: 'Sales',
          child: Column(
            children: [
              _Line(label: 'Gross sales', amount: earnings.grossSales),
              _Line(label: 'Refunds', amount: -earnings.refunds),
              const Divider(height: 20),
              _Line(label: 'Net sales', amount: earnings.netSales, bold: true),
            ],
          ),
        ),
        const SizedBox(height: 12),
        AdminSectionCard(
          title: 'How the money arrived',
          child: Column(
            children: [
              _Line(label: 'Paid online', amount: earnings.collectedOnline),
              _Line(label: 'Cash at the door', amount: earnings.collectedCash),
              _Line(label: 'UPI at the door', amount: earnings.collectedCodUpi),
              const Divider(height: 20),
              _Line(
                label: 'Still to collect',
                amount: earnings.awaitingCollection,
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        AdminSectionCard(
          title: 'Orders',
          child: Column(
            children: [
              _Line.count(label: 'Orders', value: earnings.orderCount),
              _Line.count(label: 'Cancelled', value: earnings.cancelledCount),
            ],
          ),
        ),
      ],
    );
  }
}

class _Line extends StatelessWidget {
  const _Line({required this.label, required this.amount, this.bold = false})
      : count = null;

  const _Line.count({required this.label, required int value})
      : amount = null,
        count = value,
        bold = false;

  final String label;
  final double? amount;
  final int? count;
  final bool bold;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(
        fontWeight: bold ? FontWeight.w700 : FontWeight.w500, fontSize: 13.5);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: style),
          Text(
            count != null ? '$count' : '₹${amount!.toStringAsFixed(0)}',
            style: style,
          ),
        ],
      ),
    );
  }
}
