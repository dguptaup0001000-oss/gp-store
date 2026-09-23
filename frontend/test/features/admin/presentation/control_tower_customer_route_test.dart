import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/platform_repository.dart';
import 'package:gpstore/features/admin/presentation/platform_control_tower_screen.dart';
import 'package:gpstore/features/admin/presentation/platform_customer_profile_screen.dart';
import 'package:gpstore/features/admin/presentation/platform_providers.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  testWidgets('Control Tower customer search opens the modern Customer 360',
      (tester) async {
    final adapter = FakeHttpClientAdapter();
    adapter.on('GET', '/api/platform/control/dashboard', (_) => const FakeResponse({
          'from': '2026-09-23',
          'to': '2026-09-23',
          'marketplace': {},
          'orderStatuses': {},
          'finance': {},
          'presenceAvailable': false,
        }));
    adapter.on('GET', '/api/platform/control/search', (options) {
      expect(options.queryParameters['q'], 'Deepak');
      return const FakeResponse({
        'content': [
          {
            'entityType': 'CUSTOMER',
            'entityId': 2,
            'title': 'Deepak kr.Gupta',
            'reference': 'C-2',
            'subtitle': 'Customer account',
          }
        ],
        'page': 0,
        'totalPages': 1,
        'totalElements': 1,
      });
    });
    adapter.on('GET', '/api/platform/control/customers/2/profile', (_) {
      return const FakeResponse({
        'core': {
          'identity': {
            'id': 2,
            'customerRef': 'C-2',
            'name': 'Deepak kr.Gupta',
            'email': 'deepak@example.test',
            'phone': '9876543210',
            'role': 'CUSTOMER',
            'roles': ['CUSTOMER'],
            'enabled': true,
            'active': true,
            'verified': true,
            'createdAt': '2026-09-01T08:30:00',
          },
          'orders': {
            'total': 0,
            'completed': 0,
            'active': 0,
            'cancelled': 0,
            'failed': 0,
            'returned': 0,
            'refunded': 0,
          },
          'finance': {
            'completedPurchaseValue': 0,
            'refunds': 0,
            'cancellationFees': 0,
            'averageCompletedOrder': 0,
            'lastOrderAt': null,
          },
          'reviews': 0,
          'reportedReviews': 0,
          'recentOrders': [],
        },
        'addresses': [],
        'shops': [],
        'categories': [],
        'payments': [],
        'refunds': [],
        'reviews': [],
        'returns': [],
        'activity': {
          'sessions': 0,
          'activeDays': 0,
          'totalSeconds': 0,
          'firstSessionAt': null,
          'lastSessionAt': null,
          'lastOrderAt': null,
          'note': '',
        },
        'security': [],
      });
    });

    final repository =
        PlatformRepository(apiClient: buildTestApiClient(adapter));
    await tester.pumpWidget(ProviderScope(
      overrides: [platformRepositoryProvider.overrideWithValue(repository)],
      child: const MaterialApp(home: PlatformControlTowerScreen()),
    ));
    await tester.pump();

    await tester.enterText(find.byType(TextField), 'Deepak');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pump();
    expect(find.text('Deepak kr.Gupta'), findsOneWidget);

    final resultTile = find.ancestor(
      of: find.text('Deepak kr.Gupta'),
      matching: find.byType(ListTile),
    );
    expect(resultTile, findsOneWidget);
    final onTap = tester.widget<ListTile>(resultTile).onTap;
    expect(onTap, isNotNull);
    onTap!();
    await tester.pumpAndSettle();

    expect(find.byType(PlatformCustomerProfileScreen), findsOneWidget,
        reason: 'The old generic PlatformEntity360Screen renders raw '
            'label/value maps and was the screen reached by real global search.');
  });
}
