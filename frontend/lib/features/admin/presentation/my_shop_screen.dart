import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../core/api/error_messages.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/shop_admin_models.dart';
import 'shop_self_service_providers.dart';

/// This shop, as its own shopkeeper sees it.
///
/// THE SHOP IS NOT CHOSEN HERE. Every route behind this screen acts on "the
/// shop this request is for", resolved from the staff member's own record
/// before the controller ran (§78). There is no shop id in any of these
/// calls, so there is nothing for a merchant to change and nothing for this
/// screen to have to defend.
///
/// WHY READINESS IS A SCREEN AND NOT A BANNER. A new shop that is not selling
/// has one of about six reasons, and until now a merchant had to guess which.
/// The backend already answers it; this shows the answer, and separates the
/// steps that STOP orders from the ones that only make them worse.
class MyShopScreen extends ConsumerWidget {
  const MyShopScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profileAsync = ref.watch(myShopProfileProvider);
    final readinessAsync = ref.watch(myShopReadinessProvider);
    final openWorkAsync = ref.watch(myShopOpenWorkProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('My Shop')),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(myShopProfileProvider);
          ref.invalidate(myShopReadinessProvider);
          ref.invalidate(myShopOpenWorkProvider);
        },
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            profileAsync.when(
              loading: _spinner,
              error: (error, _) => _Failed(
                what: 'shop details',
                error: error,
                onRetry: () => ref.invalidate(myShopProfileProvider),
              ),
              data: (profile) => _Profile(profile: profile),
            ),
            const SizedBox(height: 12),
            openWorkAsync.when(
              loading: _spinner,
              error: (error, _) => _Failed(
                what: 'open work',
                error: error,
                onRetry: () => ref.invalidate(myShopOpenWorkProvider),
              ),
              data: (openWork) => _OpenWork(openWork: openWork),
            ),
            const SizedBox(height: 12),
            readinessAsync.when(
              loading: _spinner,
              error: (error, _) => _Failed(
                what: 'readiness',
                error: error,
                onRetry: () => ref.invalidate(myShopReadinessProvider),
              ),
              data: (readiness) => _Readiness(readiness: readiness),
            ),
          ],
        ),
      ),
    );
  }

  static Widget _spinner() => const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
      );
}

class _Failed extends StatelessWidget {
  const _Failed({required this.what, required this.error, required this.onRetry});

  final String what;
  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      child: Column(
        children: [
          Text("Couldn't load $what: ${extractErrorMessage(error)}"),
          const SizedBox(height: 8),
          TextButton(onPressed: hapticize(onRetry), child: const Text('Retry')),
        ],
      ),
    );
  }
}

class _Profile extends StatelessWidget {
  const _Profile({required this.profile});

  final ShopProfile profile;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      title: profile.displayName ?? 'Shop ${profile.id}',
      subtitle: profile.code,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _Fact(label: 'Status', value: profile.status ?? 'Unknown'),
          // The platform's reason, shown to the merchant and to nobody else.
          // A customer is told only that the shop is not taking orders.
          if (profile.statusReason != null)
            _Fact(label: 'Reason', value: profile.statusReason!),
          if (profile.maxDeliveryRadiusKm != null)
            _Fact(
              label: 'Delivers up to',
              value: '${profile.maxDeliveryRadiusKm!.toStringAsFixed(0)} km',
            ),
          if (profile.timeZone != null)
            _Fact(label: 'Time zone', value: profile.timeZone!),
          if (profile.supportPhone != null)
            _Fact(label: 'Support phone', value: profile.supportPhone!),
        ],
      ),
    );
  }
}

class _OpenWork extends StatelessWidget {
  const _OpenWork({required this.openWork});

  final Map<String, int> openWork;

  @override
  Widget build(BuildContext context) {
    final entries = openWork.entries.where((e) => e.value > 0).toList();
    return AdminSectionCard(
      title: 'Needs attention now',
      child: entries.isEmpty
          ? const Text('Nothing waiting.')
          : Column(
              children: [
                for (final entry in entries)
                  _Fact(
                    label: entry.key.replaceAll('_', ' '),
                    value: '${entry.value}',
                  ),
              ],
            ),
    );
  }
}

class _Readiness extends StatelessWidget {
  const _Readiness({required this.readiness});

  final ShopReadiness readiness;

  @override
  Widget build(BuildContext context) {
    final blockers = readiness.outstandingBlockers;
    final advice = readiness.outstandingAdvice;

    return AdminSectionCard(
      title: readiness.canTakeOrders
          ? 'This shop is taking orders'
          : 'This shop is not taking orders',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (blockers.isEmpty && advice.isEmpty)
            const Text('Everything is set up.')
          else ...[
            for (final step in blockers) _Step(step: step),
            for (final step in advice) _Step(step: step),
          ],
        ],
      ),
    );
  }
}

class _Step extends StatelessWidget {
  const _Step({required this.step});

  final ReadinessStep step;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            step.done
                ? Icons.check_circle_outline
                : (step.blocking
                    ? Icons.error_outline
                    : Icons.info_outline),
            size: 18,
            color: step.done
                ? Colors.green
                : (step.blocking ? Colors.red : Colors.orange),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(step.name.replaceAll('_', ' '),
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                if (step.detail != null)
                  Text(step.detail!,
                      style: Theme.of(context).textTheme.bodySmall),
                // Said in words, because a red icon alone does not tell a
                // shopkeeper whether this is why nothing is selling.
                if (!step.done)
                  Text(
                    step.blocking
                        ? 'Stops orders until this is done'
                        : 'Orders still go out, but less well',
                    style: TextStyle(
                      fontSize: 11.5,
                      color: step.blocking ? Colors.red : Colors.orange,
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Fact extends StatelessWidget {
  const _Fact({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(fontSize: 13)),
          Flexible(
            child: Text(value,
                textAlign: TextAlign.right,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w600)),
          ),
        ],
      ),
    );
  }
}
