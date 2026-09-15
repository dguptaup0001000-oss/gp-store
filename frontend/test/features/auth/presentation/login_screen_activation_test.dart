import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/auth/presentation/login_screen.dart';

void main() {
  Widget host({required bool allowActivationCode}) => ProviderScope(
        child: MaterialApp(
          home: LoginScreen(
            allowRegister: false,
            allowActivationCode: allowActivationCode,
          ),
        ),
      );

  testWidgets('merchant admin login shows the first-login activation field',
      (tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(host(allowActivationCode: true));

    expect(find.text('15-character activation code'), findsOneWidget);
    expect(find.text('Required for first login only'), findsOneWidget);
    expect(find.text('Login to manage your shop'), findsOneWidget);
  });

  testWidgets('customer login does not show a merchant activation field',
      (tester) async {
    await tester.pumpWidget(host(allowActivationCode: false));

    expect(find.text('15-character activation code'), findsNothing);
    expect(find.text('Login to continue shopping'), findsOneWidget);
  });
}
