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
    await tester.pump();

    expect(find.byType(PlatformCustomerProfileScreen), findsOneWidget,
        reason: 'The old generic PlatformEntity360Screen renders raw '
            'label/value maps and was the screen reached by real global search.');
  });
}
