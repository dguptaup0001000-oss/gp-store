import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/profile/data/profile_repository.dart';

import '../../../support/test_api_client.dart';

void main() {
  test('deleteAccount sends the current password, not DELETE as auth', () async {
    setUpFakeSecureStorage();
    final adapter = FakeHttpClientAdapter();
    Map<String, dynamic>? body;
    adapter.on('DELETE', '/api/customers/me', (options) {
      body = Map<String, dynamic>.from(options.data as Map);
      return const FakeResponse(null);
    });

    final repository = ProfileRepository(apiClient: buildTestApiClient(adapter));
    await repository.deleteAccount(currentPassword: 'correct-horse-1');
    expect(body, {'currentPassword': 'correct-horse-1'});
  });
}
