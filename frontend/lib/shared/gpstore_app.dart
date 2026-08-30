import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/lifecycle/session_refresh.dart';
import '../core/notifications/push_notification_providers.dart';
import '../core/theme/app_theme.dart';
import '../features/auth/presentation/auth_providers.dart';
import '../features/profile/presentation/profile_providers.dart';

/// Lets code without a local BuildContext (FCM tap handlers) still show a
/// SnackBar on top of whatever the user is looking at.
final scaffoldMessengerKey = GlobalKey<ScaffoldMessengerState>();

typedef PushMessageHandler = void Function(WidgetRef ref, RemoteMessage message);

/// Shared MaterialApp shell. Customer and admin entrypoints pass different
/// routers and push handlers so admin-only services are not started in the
/// shop APK.
class GpstoreApp extends ConsumerWidget {
  const GpstoreApp({
    super.key,
    required this.title,
    required this.routerProvider,
    required this.onNotificationTap,
    this.onForegroundExtras,
    this.onAdminSession,
  });

  final String title;
  final Provider<GoRouter> routerProvider;
  final PushMessageHandler onNotificationTap;

  /// Extra work on a foreground push (admin print / announce). Customer
  /// passes null so those libraries are not referenced from the shop graph.
  final PushMessageHandler? onForegroundExtras;

  /// Start/stop admin-only watchers when the signed-in role is known.
  /// Customer leaves this null.
  final void Function(WidgetRef ref, String? role)? onAdminSession;

  void _showForegroundBanner(RemoteMessage message) {
    final title = message.notification?.title;
    final body = message.notification?.body;
    if (title == null && body == null) return;

    scaffoldMessengerKey.currentState?.showSnackBar(
      SnackBar(
        content: Text(
            [title, body].where((s) => s != null && s.isNotEmpty).join(' - ')),
        duration: const Duration(seconds: 4),
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);

    ref.listen<AuthState>(authControllerProvider, (previous, next) {
      final wasAuthenticated = previous?.status == AuthStatus.authenticated;
      final isAuthenticated = next.status == AuthStatus.authenticated;

      if (isAuthenticated && !wasAuthenticated) {
        ref.read(pushNotificationServiceProvider).start(
              onForegroundMessage: (message) {
                _showForegroundBanner(message);
                onForegroundExtras?.call(ref, message);
              },
              onNotificationTap: (message) => onNotificationTap(ref, message),
            );
      } else if (!isAuthenticated && wasAuthenticated) {
        ref.read(pushNotificationServiceProvider).stop();
        onAdminSession?.call(ref, null);
      }
    });

    if (onAdminSession != null) {
      ref.listen(myProfileProvider, (previous, next) {
        next.when(
          data: (profile) => onAdminSession!(ref, profile.role),
          error: (_, __) {},
          loading: () {},
        );
      });
    }

    return SessionRefresh(
      child: MaterialApp.router(
        title: title,
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light,
        routerConfig: router,
        scaffoldMessengerKey: scaffoldMessengerKey,
      ),
    );
  }
}
