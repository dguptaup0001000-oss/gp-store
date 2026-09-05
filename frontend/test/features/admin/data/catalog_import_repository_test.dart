import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/catalog_import_repository.dart';

import '../../../support/test_api_client.dart';

/// Reading back what a past import refused.
///
/// The preview carries the refused rows at upload time and they vanish with
/// the screen, leaving a history row that says "3 refused" and no way to
/// learn which three. Read back rather than downloaded: the app has no
/// file-saving code, and a storage permission on every customer install for
/// one admin screen is the worse trade.
void main() {
  setUpAll(setUpFakeSecureStorage);

  test('GETs the past run and keeps the row, the column and the suggestion',
      () async {
    final adapter = FakeHttpClientAdapter();
    adapter.on(
      'GET',
      '/api/admin/catalog/import/17/problems',
      (_) => const FakeResponse([
        {
          'row': 412,
          'field': 'Selling Price',
          'severity': 'ERROR',
          'problem': 'Selling price is above MRP.',
          'suggestion': 'Lower it to 58.00 or raise the MRP.',
        },
        {
          'row': 3,
          'field': 'Unit',
          'severity': 'WARNING',
          'problem': 'Unit "KGS" was read as kg.',
          'suggestion': null,
        },
      ]),
    );

    final problems =
        await CatalogImportRepository(apiClient: buildTestApiClient(adapter))
            .problemsFor(17);

    expect(problems, hasLength(2));
    // The row number is the whole point - "above MRP" with no row is a
    // sentence the shopkeeper cannot act on.
    expect(problems.first.row, 412);
    expect(problems.first.field, 'Selling Price');
    expect(problems.first.isError, isTrue);
    expect(problems.first.suggestion, 'Lower it to 58.00 or raise the MRP.');

    expect(problems.last.isError, isFalse,
        reason: 'a warning must not be shown as a refusal');
    expect(problems.last.suggestion, isNull);
  });

  test('a run that refused nothing comes back empty, not as a failure',
      () async {
    final adapter = FakeHttpClientAdapter();
    adapter.on('GET', '/api/admin/catalog/import/5/problems',
        (_) => const FakeResponse(<Map<String, dynamic>>[]));

    final problems =
        await CatalogImportRepository(apiClient: buildTestApiClient(adapter))
            .problemsFor(5);

    expect(problems, isEmpty);
  });
}
