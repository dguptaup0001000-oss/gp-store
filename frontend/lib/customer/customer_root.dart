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
    // A Customer row with DELIVERY_BOY is a shopper who also delivers. The
    // backend grants that account both ROLE_CUSTOMER and ROLE_DELIVERY_BOY;
    // rejecting it here contradicted the authenticated identity used by
    // wishlist and notifications. A standalone worker has no customerId, so
    // /api/customers/me is refused before a Profile can reach this builder.
    return const CustomerShell();
  }
}
