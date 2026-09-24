import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/admin_workers_repository.dart';
import 'package:gpstore/features/admin/domain/worker_models.dart';
import 'package:gpstore/features/admin/presentation/admin_providers.dart';
import 'package:gpstore/features/admin/presentation/admin_worker_profile_screen.dart';

import '../../../support/test_api_client.dart';

class _ProfileRepository extends AdminWorkersRepository {
  _ProfileRepository() : super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  final pages = <int>[];

  AdminWorker _worker() => const AdminWorker(
        id: 7,
        name: 'Deepak Kumar Gupta',
        loginEmail: 'deepak@example.test',
        mobile: '9000000000',
        vehicleType: 'BIKE',
        vehicleNumber: 'UP32 AB 1234',
        available: true,
        active: true,
        canSignIn: true,
        suspended: false,
      );

  @override
  Future<AdminWorkerProfile> profile(int id, {int page = 0, int size = 20}) async {
    pages.add(page);
    return AdminWorkerProfile(
      worker: _worker(),
      shopName: 'GP Store',
      totalAssigned: 2,
      completed: 1,
      active: 1,
      exceptions: 0,
      page: page,
      size: size,
      hasNext: page == 0,
      currentWork: const [
        AdminWorkerDelivery(
          id: 80,
          orderNumber: 'GP-ORDER-80',
          status: 'OUT_FOR_DELIVERY',
          assignedAt: null,
          deliveredAt: null,
        ),
      ],
      history: [
        AdminWorkerDelivery(
          id: page == 0 ? 81 : 82,
          orderNumber: page == 0 ? 'GP-ORDER-81' : 'GP-ORDER-82',
          status: 'DELIVERED',
        ),
      ],
    );
  }
}

void main() {
  testWidgets('Worker 360 renders shop operations and loads history by page',
      (tester) async {
    final repository = _ProfileRepository();
    await tester.pumpWidget(ProviderScope(
      overrides: [adminWorkersRepositoryProvider.overrideWithValue(repository)],
      child: const MaterialApp(home: AdminWorkerProfileScreen(workerId: 7)),
    ));
    await tester.pumpAndSettle();

    expect(repository.pages, [0]);
    expect(find.text('Worker 360'), findsOneWidget);
    expect(find.text('Deepak Kumar Gupta'), findsOneWidget);
    expect(find.text('GP Store'), findsOneWidget);
    expect(find.text('Active'), findsOneWidget);
    expect(find.text('Available'), findsOneWidget);
    expect(find.text('Bike'), findsOneWidget);
    // Delivery sections are below the overview cards in the scrolling profile.
    await tester.drag(find.byType(ListView), const Offset(0, -800));
    await tester.pumpAndSettle();
    expect(find.text('GP-ORDER-80'), findsOneWidget);
    expect(find.text('GP-ORDER-81'), findsOneWidget);
    expect(find.text('password'), findsNothing);
    expect(find.text('token'), findsNothing);

    await tester.ensureVisible(find.text('Load more history'));
    await tester.tap(find.text('Load more history'));
    await tester.pumpAndSettle();

    expect(repository.pages, [0, 1]);
    expect(find.text('GP-ORDER-82'), findsOneWidget);
    expect(find.text('GP-ORDER-81'), findsOneWidget);
  });
}
