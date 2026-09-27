import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../shared/widgets/action_feedback.dart';
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
          loading: () => const ListView(
            physics: AlwaysScrollableScrollPhysics(),
            children: [
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
                const Padding(
                  padding: EdgeInsets.only(top: 180),
                  child: Text(
                    'No requests yet. When local search cannot find something, use “I Need This”.',
                    textAlign: TextAlign.center,
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
                  ),
            ],
          ),
        ),
      ),
    );
  }
}
