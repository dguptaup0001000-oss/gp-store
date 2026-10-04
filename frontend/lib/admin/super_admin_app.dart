import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/router/app_router.dart';
import '../core/lifecycle/app_session_tracker.dart';
import '../core/lifecycle/session_refresh.dart';
import 'design/admin_theme.dart';
import 'super_admin_root.dart';

final _superAdminMessengerKey = GlobalKey<ScaffoldMessengerState>();

final superAdminRouterProvider = Provider<GoRouter>((ref) {
  return createGoRouter(
    ref: ref,
    home: const SuperAdminRootScreen(),
    allowRegister: false,
  );
});

/// Platform owner APK UI.
///
/// NO PUSH, AND THAT IS THE POINT OF THE EMPTY HANDLER. Every notification
/// this project sends is about one shop's order - NEW_ORDER and ORDER_STATUS -
/// and this app is not how anybody runs a shop. Its flavor has no Firebase
/// client (see android/app/build.gradle), lib/super_admin_main.dart never
/// initialises Firebase, and PushAvailability.firebaseReady stays false, so
/// nothing ever calls this. It exists because GpstoreApp requires a handler,
/// and a handler that navigates somewhere would be a claim that pushes arrive.
///
/// No printer, no new-order sound, no voice announcement either: all three are
/// a shop counter's tools.
class SuperAdminApp extends ConsumerWidget {
  const SuperAdminApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return AppSessionTracker(
      child: SessionRefresh(
        child: MaterialApp.router(
          title: 'GP-STORE Super Admin',
          debugShowCheckedModeBanner: false,
          theme: AdminTheme.light,
          routerConfig: ref.watch(superAdminRouterProvider),
          scaffoldMessengerKey: _superAdminMessengerKey,
        ),
      ),
    );
  }
}
