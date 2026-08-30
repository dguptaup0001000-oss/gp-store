import 'package:flutter/material.dart';

import '../features/customer_shell.dart';
import '../features/profile/domain/profile_models.dart';
import '../shared/widgets/signed_in_home.dart';
import '../shared/widgets/wrong_app_screen.dart';

/// Customer APK home. Shop only - no admin dashboard and no delivery
/// partner console (those are the Admin and Worker apps).
class CustomerRootScreen extends StatelessWidget {
  const CustomerRootScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const SignedInHome(builder: _homeFor);
  }

  static Widget _homeFor(Profile profile) {
    if (profile.role == 'ADMIN') {
      return const WrongAppScreen(
        title: 'GP-STORE',
        message:
            'This account is a store admin. Install the GP-STORE Admin app '
            'to manage the shop. This app is for shopping only.',
      );
    }
    if (profile.role == 'DELIVERY_BOY') {
      return const WrongAppScreen(
        title: 'GP-STORE',
        message:
            'Delivery partners use the GP-STORE Worker app to pack orders. '
            'This app is for shopping only.',
      );
    }
    return const CustomerShell();
  }
}
