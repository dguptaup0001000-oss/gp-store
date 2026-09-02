import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/util/app_haptics.dart';
import 'package:gpstore/features/admin/data/admin_products_repository.dart';
import 'package:gpstore/features/admin/domain/delivery_partner_models.dart';
import 'package:gpstore/features/admin/domain/worker_login_account.dart';
import 'package:gpstore/features/admin/presentation/admin_delivery_partner_form_dialog.dart';
import 'package:gpstore/features/admin/presentation/admin_providers.dart';

import '../../../support/test_api_client.dart';

/// Save is what applies the rider's login email.
///
/// THE REGRESSION THIS PINS. The address sat in a text field, in a form,
/// above a Save button - and Save did not apply it. It called only
/// updateDeliveryPartner, which never touches the login link, so the typed
/// address was discarded, the dialog closed reporting success, and the rider
/// stayed locked out of the worker app with "You don't have permission to do
/// that." Linking needed a second, separate button that nobody presses when
/// there is a Save button right there.
class _FakeRepository extends AdminProductsRepository {
  _FakeRepository({this.initial, this.linkError})
      : super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  final WorkerLoginAccount? initial;
  final Object? linkError;

  final List<String> linked = <String>[];
  final List<String> passwords = <String>[];
  int unlinkCalls = 0;
  int updateCalls = 0;

  @override
  Future<WorkerLoginAccount> getWorkerLoginAccount(int partnerId) async {
    return initial ?? WorkerLoginAccount.none;
  }

  @override
  Future<WorkerLoginAccount> linkWorkerLoginAccount(
      int partnerId, String email, String password) async {
    if (linkError != null) throw linkError!;
    linked.add(email);
    passwords.add(password);
    return WorkerLoginAccount(linked: true, email: email, canSignIn: true);
  }

  @override
  Future<WorkerLoginAccount> unlinkWorkerLoginAccount(int partnerId) async {
    unlinkCalls++;
    return WorkerLoginAccount.none;
  }

  @override
  Future<void> updateDeliveryPartner(DeliveryPartnerModel partner) async {
    updateCalls++;
  }
}

const _partner = DeliveryPartnerModel(
  id: 7,
  name: 'Deepak Kumar Gupta',
  mobile: '6388293365',
  vehicleType: 'BIKE',
);

/// Pushed through showDialog, not pumped bare: _save pops its route on
/// success, and a dialog that is the whole home widget has no route to pop.
Future<void> _open(WidgetTester tester, _FakeRepository repository) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [adminProductsRepositoryProvider.overrideWithValue(repository)],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => ElevatedButton(
            onPressed: () => showDialog<bool>(
              context: context,
              builder: (_) => const AdminDeliveryPartnerFormDialog(partner: _partner),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

Finder get _emailField => find.widgetWithText(TextField, 'Login email');
Finder get _passwordField => find.widgetWithText(TextField, 'Password');
Finder get _newPasswordField =>
    find.widgetWithText(TextField, 'New password (optional)');

void main() {
  setUpAll(setUpFakeSecureStorage);
  setUp(() {
    AppHaptics.resetForTest();
    AppHaptics.enabled = false;
  });

  testWidgets('Save links the address typed into the login field', (tester) async {
    final repository = _FakeRepository();
    await _open(tester, repository);

    await tester.enterText(_emailField, 'guptadeepak@gmail.com');
    await tester.enterText(_passwordField, 'rider-passphrase');
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(repository.linked, ['guptadeepak@gmail.com'],
        reason: 'Save must apply the address. This is the whole bug: it used to '
            'save the roster fields and silently drop the email.');
    expect(repository.passwords, ['rider-passphrase'],
        reason: 'And the password, because the shop sets it - nothing else '
            'in this system ever gives a rider one.');
    expect(repository.updateCalls, 1, reason: 'The roster fields still save too.');
  });

  testWidgets('the address is trimmed before it is sent', (tester) async {
    final repository = _FakeRepository();
    await _open(tester, repository);

    await tester.enterText(_emailField, '  guptadeepak@gmail.com  ');
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(repository.linked, ['guptadeepak@gmail.com']);
  });

  testWidgets('an unchanged address is not re-sent', (tester) async {
    final repository = _FakeRepository(
      initial: const WorkerLoginAccount(
          linked: true, email: 'rider@gmail.com', canSignIn: true),
    );
    await _open(tester, repository);

    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(repository.linked, isEmpty,
        reason: 'Editing only the vehicle number must not spend a link request.');
    expect(repository.updateCalls, 1);
  });

  testWidgets('a new password alone is enough to resend, same address', (tester) async {
    final repository = _FakeRepository(
      initial: const WorkerLoginAccount(
          linked: true, email: 'rider@gmail.com', canSignIn: true),
    );
    await _open(tester, repository);

    // Resetting a forgotten password must not require changing the address -
    // and the unchanged-address shortcut would otherwise swallow it.
    await tester.enterText(_newPasswordField, 'a-brand-new-passphrase');
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(repository.linked, ['rider@gmail.com']);
    expect(repository.passwords, ['a-brand-new-passphrase']);
  });

  testWidgets('differing only in case is not a change', (tester) async {
    final repository = _FakeRepository(
      initial: const WorkerLoginAccount(
          linked: true, email: 'Rider@Gmail.com', canSignIn: true),
    );
    await _open(tester, repository);

    await tester.enterText(_emailField, 'rider@gmail.com');
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(repository.linked, isEmpty,
        reason: 'The server matches the address case-insensitively.');
  });

  testWidgets('clearing the field and saving takes the access away', (tester) async {
    final repository = _FakeRepository(
      initial: const WorkerLoginAccount(
          linked: true, email: 'rider@gmail.com', canSignIn: true),
    );
    await _open(tester, repository);

    await tester.enterText(_emailField, '');
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(repository.unlinkCalls, 1);
    expect(repository.linked, isEmpty);
  });

  testWidgets('a refused address keeps the dialog open and says why', (tester) async {
    final repository = _FakeRepository(
      // An ApiException, because that is what the backend's refusals actually
      // arrive as - a bare Exception collapses to "Something went wrong" in
      // extractErrorMessage and would test the wrong path entirely.
      // A message the server actually produces now. The old fixture quoted
      // "register in the customer app", which was the guidance from the
      // link-only design this replaced - a fixture describing behaviour the
      // code no longer has is a trap for the next reader.
      linkError: ApiException(
          statusCode: 409,
          message: 'That address belongs to a staff account, so its password '
              'can only be changed by its owner. Leave the password blank and '
              'they sign in to the worker app with the password they already '
              'use.'),
    );
    await _open(tester, repository);

    await tester.enterText(_emailField, 'nobody@gmail.com');
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(find.textContaining('belongs to a staff account'), findsNWidgets(2),
        reason: 'Twice on purpose: under the field that caused it, AND in a '
            'SnackBar. The inline error is the one that explains which field is '
            'wrong; the SnackBar is the one you actually see, because the inline '
            'one renders below the fold with the keyboard over it.');
    expect(_emailField, findsOneWidget,
        reason: 'The dialog must stay open so the address can be corrected.');
    expect(repository.updateCalls, 0,
        reason: 'A refused login must not half-save the record.');
    expect(find.byType(SnackBar), findsOneWidget,
        reason: 'The inline error alone was missed for a whole afternoon - it '
            'renders below the password field, in a dialog that scrolls, with '
            'the keyboard over the bottom of the screen. A refusal that looks '
            'like nothing happened gets the same password retyped forever.');
  });

  testWidgets('a rider with no login is told so, not left to guess', (tester) async {
    final repository = _FakeRepository();
    await _open(tester, repository);

    // Wording changed when the shop started SETTING the credentials rather
    // than linking an account the rider had made: the status now names the
    // two fields directly above it instead of describing the consequence.
    expect(find.textContaining('Not set up yet'), findsOneWidget);
  });

  testWidgets('linked without a password is not reported as set up', (tester) async {
    // The state EVERY partner used to be in: an OTP account attached
    // perfectly well and unable to sign in. "Linked" alone would hide it.
    final repository = _FakeRepository(
      initial: const WorkerLoginAccount(
          linked: true, email: 'rider@gmail.com', canSignIn: false),
    );
    await _open(tester, repository);

    expect(find.textContaining('no password'), findsOneWidget);
  });
}
