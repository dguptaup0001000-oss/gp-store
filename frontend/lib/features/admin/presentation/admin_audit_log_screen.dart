import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminAuditLogScreen extends ConsumerWidget {
  const AdminAuditLogScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final logAsync = ref.watch(adminAuditLogProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Audit Log')),
      body: logAsync.when(
        loading: () => const AdminListSkeleton(),
        error: (error, stackTrace) => AdminErrorState(
          // Shows the real failure reason rather than one static
          // string - an admin who can read the cause can act on it.
          message: "Couldn't load audit log: ${extractErrorMessage(error)}",
          onRetry: hapticize(() => ref.invalidate(adminAuditLogProvider)),
        ),
        data: (entries) {
          if (entries.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.history_outlined,
              title: 'No audit log entries yet',
              message: 'Actions staff take in this console are recorded here.',
            );
          }

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(adminAuditLogProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: entries.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, index) {
                final entry = entries[index];
                return Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(color: AdminColors.surface, borderRadius: BorderRadius.circular(10)),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(entry.action.replaceAll('_', ' '), style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
                          if (entry.occurredAt != null)
                            Text(_formatDate(entry.occurredAt!), style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11)),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '${entry.entityType}${entry.entityId != null ? ' #${entry.entityId}' : ''}',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12),
                      ),
                      if (entry.details != null) ...[
                        const SizedBox(height: 4),
                        Text(entry.details!, style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary)),
                      ],
                      if (entry.actorEmail != null) ...[
                        const SizedBox(height: 4),
                        Text('By: ${entry.actorEmail} (${entry.actorRole ?? "unknown"})',
                            style: const TextStyle(fontSize: 11, color: AdminColors.textSecondary)),
                      ],
                    ],
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }

  String _formatDate(String iso) {
    try {
      final date = DateTime.parse(iso);
      return '${date.day}/${date.month} ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
    } catch (_) {
      return iso;
    }
  }
}
