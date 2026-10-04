import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../shared/widgets/action_feedback.dart';
import '../data/demand_repository.dart';
import 'demand_providers.dart';
import 'i_need_this_screen.dart';

class DemandRequestsScreen extends ConsumerWidget {
  const DemandRequestsScreen({super.key});

  Future<void> _finish(
    BuildContext context,
    WidgetRef ref,
    int id, {
    required bool cancel,
  }) async {
    try {
      final repository = ref.read(demandRepositoryProvider);
      if (cancel) {
        await repository.cancel(id);
      } else {
        await repository.close(id);
      }
      ref.invalidate(myDemandRequestsProvider);
      if (context.mounted) {
        showActionSuccess(
          context,
          cancel ? 'Request cancelled.' : 'Request closed.',
        );
      }
    } catch (error) {
      if (context.mounted) {
        showActionFailure(context, extractErrorMessage(error));
      }
    }
  }

  Future<void> _report(
    BuildContext context,
    WidgetRef ref,
    DemandResponse response,
  ) async {
    final reason = await showDialog<String>(
      context: context,
      builder: (dialogContext) => SimpleDialog(
        title: Text('Report ${response.shopName}'),
        children: [
          for (final option in const {
            'SPAM': 'Spam',
            'MISLEADING': 'Misleading availability or price',
            'INAPPROPRIATE': 'Inappropriate message',
            'OTHER': 'Other',
          }.entries)
            SimpleDialogOption(
              onPressed: () => Navigator.pop(dialogContext, option.key),
              child: Text(option.value),
            ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
        ],
      ),
    );
    if (reason == null || !context.mounted) return;
    try {
      await ref.read(demandRepositoryProvider).reportResponse(
            response.id,
            reason: reason,
            blockShop: true,
          );
      ref.invalidate(myDemandRequestsProvider);
      if (context.mounted) {
        showActionSuccess(context, 'Response reported and shop blocked.');
      }
    } catch (error) {
      if (context.mounted) {
        showActionFailure(context, extractErrorMessage(error));
      }
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final requests = ref.watch(myDemandRequestsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('My Requests')),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(myDemandRequestsProvider);
          await ref.read(myDemandRequestsProvider.future);
        },
        child: requests.when(
          loading: () => ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            children: const [
              SizedBox(height: 240),
              Center(child: CircularProgressIndicator()),
            ],
          ),
          error: (error, _) => ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(24),
            children: [
              Text(
                extractErrorMessage(error),
                textAlign: TextAlign.center,
              ),
              TextButton(
                onPressed: () => ref.invalidate(myDemandRequestsProvider),
                child: const Text('Retry'),
              ),
            ],
          ),
          data: (items) => ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(12),
            children: [
              if (items.isEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 180, left: 16, right: 16),
                  child: Column(
                    children: [
                      const Text(
                        'No requests yet. When local search cannot find something, use “I Need This”.',
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 12),
                      OutlinedButton.icon(
                        onPressed: () => Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (_) => const INeedThisScreen(),
                          ),
                        ),
                        icon: const Icon(Icons.add),
                        label: const Text('Create a request'),
                      ),
                    ],
                  ),
                )
              else
                for (final request in items)
                  DemandRequestCard(
                    request: request,
                    onClose: request.status == 'OPEN'
                        ? () => _finish(context, ref, request.id, cancel: false)
                        : null,
                    onCancel: request.status == 'OPEN'
                        ? () => _finish(context, ref, request.id, cancel: true)
                        : null,
                    onReportResponse: (response) =>
                        _report(context, ref, response),
                  ),
            ],
          ),
        ),
      ),
    );
  }
}
