import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/catalog_import_models.dart';

/// What the import screen decides from the server's summary.
///
/// The counts are the SERVER's - nothing here recomputes them. These tests
/// pin the two decisions the screen makes on top of them: whether pressing
/// Import can do anything, and whether the run already happened.
void main() {
  CatalogImportSummary summaryOf({
    required int total,
    required int errors,
    String status = 'PREVIEWED',
  }) {
    return CatalogImportSummary.fromJson({
      'runId': 1,
      'filename': 'prices.csv',
      'mode': 'IMPORT',
      'status': status,
      'totalRows': total,
      'validRows': total - errors,
      'warningRows': 0,
      'errorRows': errors,
      'createdCount': 0,
      'updatedCount': 0,
      'problems': <Map<String, dynamic>>[],
    });
  }

  test('a sheet where every row is broken offers nothing to import', () {
    // Otherwise the shopkeeper taps Import, sees "0 created", and cannot tell
    // whether the import worked or the file did.
    expect(summaryOf(total: 40, errors: 40).hasImportableRows, isFalse);
  });

  test('a sheet with some good rows imports the good ones', () {
    expect(summaryOf(total: 40, errors: 3).hasImportableRows, isTrue);
  });

  test('an empty sheet is not importable', () {
    expect(summaryOf(total: 0, errors: 0).hasImportableRows, isFalse);
  });

  test('only a COMMITTED run counts as done', () {
    expect(summaryOf(total: 5, errors: 0).committed, isFalse);
    expect(
      summaryOf(total: 5, errors: 0, status: 'COMMITTED').committed,
      isTrue,
    );
    // A run the server FAILED is not a run that imported anything, and must
    // not render as "Imported."
    expect(summaryOf(total: 5, errors: 0, status: 'FAILED').committed, isFalse);
  });

  test('a problem row survives the round trip, suggestion and all', () {
    final summary = CatalogImportSummary.fromJson({
      'runId': 9,
      'status': 'PREVIEWED',
      'totalRows': 1,
      'errorRows': 1,
      'problems': [
        {
          'row': 412,
          'field': 'Selling Price',
          'severity': 'ERROR',
          'problem': 'Selling price is above MRP.',
          'suggestion': 'Lower it to 58.00 or raise the MRP.',
        },
      ],
    });

    expect(summary.problems, hasLength(1));
    final problem = summary.problems.single;
    expect(problem.row, 412);
    expect(problem.field, 'Selling Price');
    expect(problem.isError, isTrue);
    expect(problem.suggestion, 'Lower it to 58.00 or raise the MRP.');
  });

  test('a WARNING is not shown as an error', () {
    const warning = CatalogImportProblem(
      row: 3,
      field: 'Unit',
      severity: 'WARNING',
      problem: 'Unit "KGS" was read as kg.',
      suggestion: null,
    );
    expect(warning.isError, isFalse);
  });
}
