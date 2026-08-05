import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import 'admin_providers.dart';

class AdminAuditLogScreen extends ConsumerWidget {
  const AdminAuditLogScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final logAsync = ref.watch(adminAuditLogProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Audit Log')),
      body: logAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(extractErrorMessage(error), textAlign: TextAlign.center),
              TextButton(onPressed: () => ref.invalidate(adminAuditLogProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (entries) {
          if (entries.isEmpty) {
            return const Center(
              child: Text('No audit log entries yet', style: TextStyle(color: AppColors.textSecondary)),
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
                  decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(10)),
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
                        Text(entry.details!, style: const TextStyle(fontSize: 11, color: AppColors.textSecondary)),
                      ],
                      if (entry.actorEmail != null) ...[
                        const SizedBox(height: 4),
                        Text('By: ${entry.actorEmail} (${entry.actorRole ?? "unknown"})',
                            style: const TextStyle(fontSize: 11, color: AppColors.textSecondary)),
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
