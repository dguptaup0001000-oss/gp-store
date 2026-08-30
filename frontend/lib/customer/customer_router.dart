import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/router/app_router.dart';
import '../features/auth/presentation/register_screen.dart';
import 'customer_root.dart';

final customerRouterProvider = Provider<GoRouter>((ref) {
  return createGoRouter(
    ref: ref,
    home: const CustomerRootScreen(),
    allowRegister: true,
    extraRoutes: [
      GoRoute(
        path: '/register',
        builder: (context, state) => const RegisterScreen(),
      ),
    ],
  );
});
