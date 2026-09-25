import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/platform_repository.dart';
import 'package:gpstore/features/admin/presentation/admin_worker_profile_screen.dart';
import 'package:gpstore/features/admin/presentation/platform_providers.dart';
import 'package:gpstore/features/admin/presentation/platform_resource_screen.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  testWidgets('Super Admin worker row opens the backend Worker 360 profile',
      (tester) async {
    final adapter = FakeHttpClientAdapter();
    adapter.on('GET', '/api/platform/control/workers', (_) => const FakeResponse({
          'content': [
            {'id': 7, 'name': 'Deepak Kumar Gupta', 'shopName': 'GP Store', 'active': true}
          ],
          'page': 0,
          'totalPages': 1,
          'totalElements': 1,
        }));
    adapter.on('GET', '/api/platform/control/workers/7/profile', (_) => const FakeResponse({
          'worker': {
            'id': 7,
            'name': 'Deepak Kumar Gupta',
            'mobile': '9000000000',
            'loginEmail': 'deepak@example.test',
            'available': true,
            'active': true,
          },
          'shopId': 6,
          'shopName': 'GP Store',
          'totalAssigned': 0,
          'completed': 0,
          'active': 0,
          'exceptions': 0,
          'page': 0,
          'size': 20,
          'hasNext': false,
          'currentWork': [],
          'history': [],
        }));
    final client = buildTestApiClient(adapter);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        platformRepositoryProvider.overrideWithValue(PlatformRepository(apiClient: client)),
      ],
      child: const MaterialApp(
        home: PlatformResourceScreen(
          resource: 'workers', title: 'Workers', icon: Icons.badge_outlined,
        ),
      ),
    ));
    await tester.pumpAndSettle();

    final row = find.ancestor(
      of: find.text('Deepak Kumar Gupta'),
      matching: find.byType(ListTile),
    );
    expect(row, findsOneWidget);
    await tester.tap(row);
    await tester.pumpAndSettle();

    expect(find.byType(AdminWorkerProfileScreen), findsOneWidget);
    expect(find.text('Worker 360'), findsOneWidget);
    expect(find.text('GP Store'), findsOneWidget);
    expect(find.text('6'), findsOneWidget);
    expect(find.text('••••••0000'), findsOneWidget);
    expect(find.text('9000000000'), findsNothing);
  });
}
