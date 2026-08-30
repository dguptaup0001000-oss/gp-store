import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/router/app_router.dart';
import 'admin_root.dart';

final adminRouterProvider = Provider<GoRouter>((ref) {
  return createGoRouter(
    ref: ref,
    home: const AdminRootScreen(),
    allowRegister: false,
  );
});
