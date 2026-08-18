import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../orders/presentation/order_detail_screen.dart';
import '../domain/notification_models.dart';
import 'notifications_providers.dart';

class NotificationsScreen extends ConsumerWidget {
  const NotificationsScreen({super.key});

  Future<void> _openNotification(BuildContext context, WidgetRef ref, AppNotification notification) async {
    if (!notification.isRead) {
      try {
        await ref.read(notificationsRepositoryProvider).markAsRead(notification.id);
        ref.invalidate(myNotificationsProvider);
        ref.invalidate(unreadNotificationCountProvider);
      } catch (_) {
        // Non-critical - opening the notification still works even if
        // marking it read fails silently in the background.
      }
    }

    if (notification.order != null && context.mounted) {
      Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => OrderDetailScreen(orderId: notification.order!.id)),
      );
    }
  }

  Future<void> _delete(BuildContext context, WidgetRef ref, AppNotification notification) async {
    try {
      await ref.read(notificationsRepositoryProvider).deleteNotification(notification.id);
      ref.invalidate(myNotificationsProvider);
      ref.invalidate(unreadNotificationCountProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final notificationsAsync = ref.watch(myNotificationsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Notifications'),
        actions: [
          TextButton(
            onPressed: () async {
              try {
                await ref.read(notificationsRepositoryProvider).markAllAsRead();
                ref.invalidate(myNotificationsProvider);
                ref.invalidate(unreadNotificationCountProvider);
              } catch (_) {
                // Best-effort - the list just won't reflect it until next successful retry.
              }
            },
            child: const Text('Mark all read'),
          ),
        ],
      ),
      body: notificationsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load notifications: ${extractErrorMessage(error)}"),
              TextButton(onPressed: () => ref.invalidate(myNotificationsProvider), child: const Text('Retry')),
            ],
          ),
        ),
        data: (page) {
          final notifications = page.notifications;
          if (notifications.isEmpty) {
            return const Center(
              child: Text('No notifications yet', style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          final hasMore = ref.read(myNotificationsProvider.notifier).hasMore;

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(myNotificationsProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: notifications.length + (hasMore ? 1 : 0),
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, index) {
                if (index == notifications.length) {
                  WidgetsBinding.instance.addPostFrameCallback(
                    (_) => ref.read(myNotificationsProvider.notifier).loadMore(),
                  );
                  return const Center(child: CircularProgressIndicator(strokeWidth: 2));
                }

                final notification = notifications[index];
                return Dismissible(
                  key: ValueKey(notification.id),
                  direction: DismissDirection.endToStart,
                  background: Container(
                    alignment: Alignment.centerRight,
                    padding: const EdgeInsets.only(right: 20),
                    decoration: BoxDecoration(color: AppColors.error, borderRadius: BorderRadius.circular(12)),
                    child: const Icon(Icons.delete_outline, color: Colors.white),
                  ),
                  onDismissed: (_) => _delete(context, ref, notification),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(12),
                    onTap: () => _openNotification(context, ref, notification),
                    child: Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: notification.isRead ? AppColors.cardBackground : AppColors.primary.withValues(alpha: 0.06),
                        borderRadius: BorderRadius.circular(12),
                        border: notification.isRead ? null : Border.all(color: AppColors.primary.withValues(alpha: 0.3)),
                      ),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (!notification.isRead)
                            Container(
                              margin: const EdgeInsets.only(top: 6, right: 8),
                              width: 8,
                              height: 8,
                              decoration: const BoxDecoration(color: AppColors.primary, shape: BoxShape.circle),
                            )
                          else
                            const SizedBox(width: 16),
                          const Icon(Icons.notifications_outlined, color: AppColors.primary),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  notification.title,
                                  style: TextStyle(fontWeight: notification.isRead ? FontWeight.w600 : FontWeight.w800),
                                ),
                                const SizedBox(height: 4),
                                Text(notification.message, style: Theme.of(context).textTheme.bodyMedium),
                                const SizedBox(height: 6),
                                Text(
                                  _formatDate(notification.sentAt),
                                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
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
      return '${date.day}/${date.month}/${date.year}, ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
    } catch (_) {
      return iso;
    }
  }
}
