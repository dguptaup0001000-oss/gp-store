import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/auth/domain/auth_models.dart';

/// Login is the only response allowed through, so it is the only place the
/// app can learn that the account owes a password change.
///
/// While the flag is set the backend refuses every route but
/// /api/auth/change-password - /api/customers/me included - so there is no
/// second request that could discover this. If the login response did not
/// carry it, a merchant would land on a console whose every call fails.
void main() {
  Map<String, dynamic> loginBody({Object? mustChange}) => {
        'token': 'header.payload.signature',
        'refreshToken': 'refresh-token',
        'customerId': 41,
        'email': 'owner@kirana.test',
        'role': 'ADMIN',
        if (mustChange != null) 'mustChangePassword': mustChange,
      };

  test('a merchant on a one-time password is told so', () {
    final auth = AuthResponse.fromJson(loginBody(mustChange: true));

    expect(auth.mustChangePassword, isTrue);
    // And the session is real: the gate is a redirect, not a refusal to sign
    // in, so the token has to be usable for the change call itself.
    expect(auth.token, isNotEmpty);
    expect(auth.refreshToken, isNotEmpty);
  });

  test('an ordinary account is told it owes nothing', () {
    expect(
      AuthResponse.fromJson(loginBody(mustChange: false)).mustChangePassword,
      isFalse,
    );
  });

  test('a backend that does not send the field still logs people in', () {
    // THE DEPLOY-ORDER CASE. An APK in a customer's hand is older than the
    // server for as long as it takes them to update, and briefly newer than
    // it during a rollback. A `required` field here would crash login itself
    // - the same class of bug as the OTP accounts whose email is genuinely
    // null - so the default is false and absence means "owes nothing".
    final auth = AuthResponse.fromJson(loginBody());

    expect(auth.mustChangePassword, isFalse);
  });

  test('an OTP-only account with no email is unaffected', () {
    final body = loginBody(mustChange: false)..remove('email');

    final auth = AuthResponse.fromJson(body);
    expect(auth.email, isNull);
    expect(auth.mustChangePassword, isFalse);
  });
}
