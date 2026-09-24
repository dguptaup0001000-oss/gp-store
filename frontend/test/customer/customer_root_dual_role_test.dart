import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/customer/customer_root.dart';
import 'package:gpstore/features/customer_shell.dart';
import 'package:gpstore/features/profile/domain/profile_models.dart';
import 'package:gpstore/features/profile/presentation/profile_providers.dart';

void main() {
  testWidgets('a customer who also delivers still enters the Customer app',
      (tester) async {
    const riderCustomer = Profile(
      id: 2,
      fullName: 'Customer Rider',
      role: 'DELIVERY_BOY',
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        myProfileProvider.overrideWith((ref) async => riderCustomer),
      ],
      child: const MaterialApp(home: CustomerRootScreen()),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(CustomerShell), findsOneWidget);
    expect(find.textContaining('Worker app'), findsNothing);
  });

  testWidgets('an admin account is still directed to the Admin app',
      (tester) async {
    const admin = Profile(id: 3, fullName: 'Merchant', role: 'ADMIN');
    await tester.pumpWidget(ProviderScope(
      overrides: [
        myProfileProvider.overrideWith((ref) async => admin),
      ],
      child: const MaterialApp(home: CustomerRootScreen()),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(CustomerShell), findsNothing);
    expect(find.textContaining('Admin app'), findsOneWidget);
  });
}
