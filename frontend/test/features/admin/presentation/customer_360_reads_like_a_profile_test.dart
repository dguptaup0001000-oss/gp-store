import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/platform_repository.dart';
import 'package:gpstore/features/admin/presentation/platform_customer_profile_screen.dart';
import 'package:gpstore/features/admin/presentation/platform_providers.dart';

import '../../../support/test_api_client.dart';

/// The Super Admin Customer 360, tested through the screen an operator opens.
///
/// <h2>Why this goes through the repository and not around it</h2>
///
/// The provider is overridden with a REAL PlatformRepository pointed at a
/// fake HTTP adapter, not with a hand-written stub returning ready-made
/// objects. That means the JSON in this file is the JSON the backend sends,
/// it is decoded by the code that decodes it in production, and a field
/// renamed on either side breaks this test rather than shipping a blank
/// section to a phone. That is the failure this whole round of work exists
/// to stop: a feature that passes its own unit tests and shows nothing in
/// the APK.
void main() {
  setUpAll(setUpFakeSecureStorage);

  /// The screen is a long scrolling column of nine cards. The default
  /// 800x600 test window builds roughly the first two, so an assertion about
  /// the security card at the bottom would otherwise fail for a reason that
  /// has nothing to do with the screen.
  void tall(WidgetTester tester, {double height = 5000}) {
    tester.view.physicalSize = Size(1100, height);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  /// One customer, as the backend composes them.
  ///
  /// The numbers are chosen so a rounding bug is visible: 2673.00 is the
  /// figure the spec asks to see written out, and 1499.50 is the one that a
  /// whole-rupee formatter would silently turn into 1,500.
  Map<String, dynamic> profile({
    bool active = true,
    String? photo,
    List<Map<String, dynamic>> addresses = const [],
    List<Map<String, dynamic>> security = const [],
  }) =>
      {
        'core': {
          'identity': {
            'id': 42,
            'customerRef': 'C-42',
            'name': 'Anita Sharma',
            'email': 'a***a@example.test',
            'phone': '98***3210',
            'role': 'CUSTOMER',
            'enabled': true,
            'active': active,
            'verified': true,
            'createdAt': '2025-03-14T09:30:00',
            'profileImageUrl': photo,
          },
          'orders': {
            'total': 37,
            'completed': 31,
            'active': 1,
            'cancelled': 3,
            'failed': 0,
            'returned': 2,
            'refunded': 2,
          },
          'finance': {
            'completedPurchaseValue': 2673.00,
            'refunds': 1499.50,
            'cancellationFees': 0,
            'averageCompletedOrder': 86.23,
            'lastOrderAt': '2026-08-30T11:00:00',
          },
          'reviews': 4,
          'reportedReviews': 0,
          'recentOrders': const [],
        },
        'addresses': addresses,
        'shops': const [],
        'categories': const [],
        'payments': const [],
        'refunds': const [],
        'reviews': const [],
        'returns': const [],
        'activity': const {
          'sessions': 12,
          'activeDays': 9,
          'totalSeconds': 4200,
          'firstSessionAt': '2025-03-14T09:31:00',
          'lastSessionAt': '2026-09-01T18:02:00',
          'lastOrderAt': '2026-08-30T11:00:00',
          'note': '',
        },
        'security': security,
      };

  /// 24 orders so the first page is full and a second page exists.
  Map<String, dynamic> ordersPage(int page, {int size = 10, int total = 24}) {
    final start = page * size;
    final count = (total - start).clamp(0, size);
    return {
      'content': [
        for (var i = 0; i < count; i++)
          {
            'id': start + i + 1,
            'orderNumber': 'GP-${1000 + start + i}',
            'status': 'DELIVERED',
            'total': 250.25,
            'orderedAt': '2026-08-0${(i % 9) + 1}T10:00:00',
            'shop': 'Sharma Kirana',
          }
      ],
      'page': page,
      'size': size,
      'totalElements': total,
      'totalPages': (total / size).ceil(),
    };
  }

  ({Widget widget, FakeHttpClientAdapter adapter}) host({
    bool active = true,
    String? photo,
    List<Map<String, dynamic>> addresses = const [],
    List<Map<String, dynamic>> security = const [],
    List<RequestLine>? recorder,
  }) {
    final adapter = FakeHttpClientAdapter();
    adapter.on('GET', '/api/platform/control/customers/42/profile',
        (_) => FakeResponse(profile(
            active: active,
            photo: photo,
            addresses: addresses,
            security: security)));
    adapter.on('GET', '/api/platform/control/orders', (options) {
      final page = (options.queryParameters['page'] as num?)?.toInt() ?? 0;
      recorder?.add(RequestLine('GET', '/api/platform/control/orders',
          Map<String, dynamic>.from(options.queryParameters)));
      return FakeResponse(ordersPage(page));
    });
    adapter.on('PUT', '/api/platform/control/customers/42/status', (options) {
      recorder?.add(RequestLine('PUT', '/api/platform/control/customers/42/status',
          Map<String, dynamic>.from(options.data as Map)));
      final body = Map<String, dynamic>.from(options.data as Map);
      return FakeResponse({'customerId': 42, 'active': body['active']});
    });

    return (
      adapter: adapter,
      widget: ProviderScope(
        overrides: [
          platformRepositoryProvider.overrideWithValue(
              PlatformRepository(apiClient: buildTestApiClient(adapter))),
        ],
        child: const MaterialApp(
          home: PlatformCustomerProfileScreen(customerId: 42, title: 'Anita Sharma'),
        ),
      ),
    );
  }

  group('The header', () {
    testWidgets('draws initials when the customer has no photo', (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      // AS, from Anita Sharma. Not a stock face, not a placeholder person.
      expect(find.text('AS'), findsOneWidget);
      expect(find.text('Anita Sharma'), findsWidgets);
      expect(find.text('C-42'), findsOneWidget);
      expect(find.text('Active'), findsOneWidget);
      expect(find.text('Verified'), findsOneWidget);
    });

    testWidgets('says Barred, not Active, for an account that is off',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host(active: false).widget);
      await tester.pumpAndSettle();

      expect(find.text('Barred'), findsOneWidget);
      expect(find.text('Active'), findsNothing);
    });
  });

  group('Contact and addresses', () {
    testWidgets('says so when no address is saved, rather than hiding it',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      expect(find.text('No address saved'), findsOneWidget);
    });

    testWidgets('draws the saved address when there is one', (tester) async {
      tall(tester);
      await tester.pumpWidget(host(addresses: [
        {
          'id': 1,
          'label': 'Home',
          'line': '14 Nehru Marg',
          'area': 'Civil Lines',
          'city': 'Kanpur',
          'pincode': '208001',
          'isDefault': true,
        }
      ]).widget);
      await tester.pumpAndSettle();

      expect(find.text('No address saved'), findsNothing);
      expect(find.text('Home'), findsOneWidget);
      expect(find.text('14 Nehru Marg, Civil Lines, Kanpur, 208001'), findsOneWidget);
    });

    testWidgets('masks contact details and offers an audited way to see them',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      expect(find.text('a***a@example.test'), findsOneWidget);
      expect(find.text('98***3210'), findsOneWidget);
      expect(find.text('Show'), findsNWidgets(2));
    });
  });

  group('The numbers', () {
    testWidgets('leads with statistic cards, not a list of label-value rows',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      expect(find.text('Orders'), findsWidgets);
      expect(find.text('37'), findsOneWidget);
      expect(find.text('Completed'), findsOneWidget);
      expect(find.text('31'), findsOneWidget);
      expect(find.text('Lifetime spend'), findsOneWidget);
      expect(find.text('Active days'), findsOneWidget);
    });

    /// THE FORMAT THE SPEC ASKED FOR, to the paise.
    ///
    /// 1499.50 is the assertion that bites: a whole-rupee formatter renders
    /// it as ₹1,500 - off by fifty paise and rounded the wrong way for a
    /// refund somebody is querying.
    testWidgets('writes money to the paise with Indian grouping',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      expect(find.text('₹2,673.00'), findsWidgets);
      expect(find.text('₹1,499.50'), findsOneWidget);
      // Net to the platform: paid minus refunded.
      expect(find.text('₹1,173.50'), findsOneWidget);
    });

    testWidgets('shows what a typical order came to, and when the last one was',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host().widget);
      await tester.pumpAndSettle();

      expect(find.text('Typical order'), findsOneWidget);
      expect(find.text('₹86.23'), findsOneWidget);
      expect(find.text('Last order'), findsOneWidget);
      expect(find.text('30/08/2026 11:00'), findsOneWidget);
    });

    /// A customer who has never completed an order has no typical order. Zero
    /// is a number they earned; this is the absence of one, and a console
    /// that prints Rs 0.00 invites somebody to act on a figure that is not
    /// about this person at all.
    testWidgets('prints a dash, not zero, when they have never completed one',
        (tester) async {
      tall(tester);
      final adapter = FakeHttpClientAdapter();
      final blank = profile();
      (blank['core'] as Map)['finance'] = {
        'completedPurchaseValue': 0,
        'refunds': 0,
        'cancellationFees': 0,
        'averageCompletedOrder': 0,
        'lastOrderAt': null,
      };
      adapter.on('GET', '/api/platform/control/customers/42/profile',
          (_) => FakeResponse(blank));
      adapter.on('GET', '/api/platform/control/orders',
          (_) => FakeResponse(ordersPage(0, total: 0)));

      await tester.pumpWidget(ProviderScope(
        overrides: [
          platformRepositoryProvider.overrideWithValue(
              PlatformRepository(apiClient: buildTestApiClient(adapter))),
        ],
        child: const MaterialApp(
          home: PlatformCustomerProfileScreen(customerId: 42, title: 'Anita Sharma'),
        ),
      ));
      await tester.pumpAndSettle();

      // SCOPED TO THE ONE FACT. "Paid for goods: Rs 0.00" is TRUE of a
      // customer who never ordered, and asserting no zero appears anywhere
      // would be asserting that a true statement is a bug.
      final typicalOrder = find
          .ancestor(of: find.text('Typical order'), matching: find.byType(Column))
          .first;
      expect(find.descendant(of: typicalOrder, matching: find.text('—')),
          findsOneWidget,
          reason: 'a typical order of zero is not a fact about this customer');

      final lastOrder = find
          .ancestor(of: find.text('Last order'), matching: find.byType(Column))
          .first;
      expect(find.descendant(of: lastOrder, matching: find.text('—')),
          findsOneWidget,
          reason: 'never ordered is a dash, never today');

      // The money rows are a different matter, and their zeros are real.
      expect(find.text('₹0.00'), findsWidgets);
    });
  });

  group('Order history', () {
    testWidgets('asks the server for one page, filtered by this customer',
        (tester) async {
      tall(tester);
      final recorder = <RequestLine>[];
      await tester.pumpWidget(host(recorder: recorder).widget);
      await tester.pumpAndSettle();

      final call = recorder.firstWhere((r) => r.path.endsWith('/orders'));
      expect(call.payload['customerId'], 42,
          reason: 'the whole order table must not be downloaded and filtered here');
      expect(call.payload['page'], 0);
      expect(call.payload['size'], 10);

      expect(find.text('GP-1000'), findsOneWidget);
      expect(find.text('GP-1009'), findsOneWidget);
      expect(find.text('GP-1010'), findsNothing);
      expect(find.text('Showing 10 of 24'), findsOneWidget);
    });

    testWidgets('fetches the next page on demand', (tester) async {
      tall(tester);
      final recorder = <RequestLine>[];
      await tester.pumpWidget(host(recorder: recorder).widget);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Show 10 more'));
      await tester.pumpAndSettle();

      final pages = recorder
          .where((r) => r.path.endsWith('/orders'))
          .map((r) => r.payload['page'])
          .toList();
      expect(pages, [0, 1]);
      expect(find.text('GP-1010'), findsOneWidget);
      expect(find.text('Showing 20 of 24'), findsOneWidget);
    });
  });

  group('Security', () {
    testWidgets('shows what was done to the account, with the reason',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host(security: [
        {
          'id': 9,
          'action': 'CUSTOMER_ACCOUNT_DEACTIVATED',
          'actorEmail': 'ops@gpstore.co.in',
          'actorRole': 'PLATFORM_ADMIN',
          'entityType': 'Customer',
          'entityId': 42,
          'previousState': 'ACTIVE',
          'newState': 'INACTIVE',
          'reason': 'Fraud investigation GP-4471',
          'occurredAt': '2026-09-02T12:00:00',
        }
      ]).widget);
      await tester.pumpAndSettle();

      expect(find.text('Customer account deactivated'), findsOneWidget);
      expect(find.text('Active → Inactive'), findsOneWidget);
      expect(find.text('by ops@gpstore.co.in'), findsOneWidget);
      expect(find.text('"Fraud investigation GP-4471"'), findsOneWidget);
    });

    testWidgets('will not bar an account without a reason', (tester) async {
      tall(tester);
      final recorder = <RequestLine>[];
      await tester.pumpWidget(host(recorder: recorder).widget);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Bar this account'));
      await tester.pumpAndSettle();

      final confirm = find.widgetWithText(FilledButton, 'Bar the account');
      expect(tester.widget<FilledButton>(confirm).onPressed, isNull,
          reason: 'a blank reason must not be pressable');

      await tester.enterText(find.byType(TextField), 'ok');
      await tester.pumpAndSettle();
      expect(tester.widget<FilledButton>(confirm).onPressed, isNull,
          reason: 'two characters is not a reason');

      expect(recorder.where((r) => r.method == 'PUT'), isEmpty);
    });

    testWidgets('sends the reason with the change and reloads', (tester) async {
      tall(tester);
      final recorder = <RequestLine>[];
      await tester.pumpWidget(host(recorder: recorder).widget);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Bar this account'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'Fraud investigation GP-4471');
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Bar the account'));
      await tester.pumpAndSettle();

      final put = recorder.singleWhere((r) => r.method == 'PUT');
      expect(put.payload['active'], false);
      expect(put.payload['reason'], 'Fraud investigation GP-4471');
    });

    testWidgets('offers Restore, not Bar, for an account already off',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host(active: false).widget);
      await tester.pumpAndSettle();

      expect(find.text('Restore this account'), findsOneWidget);
      expect(find.text('Bar this account'), findsNothing);
    });
  });
}

/// One request the screen actually made, so a test can assert on it.
class RequestLine {
  RequestLine(this.method, this.path, this.payload);

  final String method;
  final String path;
  final Map<String, dynamic> payload;
}
