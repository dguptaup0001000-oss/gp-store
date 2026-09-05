/// The canonical column headings, in the order the server's template writes
/// them. Kept in step with backend ImportColumn by
/// catalog_import_columns_test.dart, which reads that Java file and fails if
/// the two drift - a heading that no longer matches would be silently ignored
/// by the importer, which is the worst way for this to break.
const List<String> catalogImportColumns = [
  'SKU',
  'Barcode',
  'Product Name',
  'Brand',
  'Category',
  'Subcategory',
  'Variant Value',
  'Unit',
  'MRP',
  'Selling Price',
  'Cost Price',
  'Discount',
  'Stock',
  'Low Stock Threshold',
  'Weight Grams',
  'GST Rate',
  'Description',
  'Image 1',
  'Image 2',
  'Image 3',
  'Image 4',
  'Image 5',
  'Featured',
  'Bestseller',
  'Active',
];

/// What the server says about a catalogue spreadsheet.
///
/// Mirrors CatalogImportService.ImportSummary. The counts are the SERVER's,
/// never recomputed here - a client that counted its own errors would be
/// telling the shopkeeper what it hoped the import would do rather than what
/// the server actually checked.
class CatalogImportSummary {
  const CatalogImportSummary({
    required this.runId,
    required this.filename,
    required this.mode,
    required this.status,
    required this.totalRows,
    required this.validRows,
    required this.warningRows,
    required this.errorRows,
    required this.createdCount,
    required this.updatedCount,
    required this.problems,
  });

  final int runId;
  final String filename;
  final String mode;
  final String status;
  final int totalRows;
  final int validRows;
  final int warningRows;
  final int errorRows;
  final int createdCount;
  final int updatedCount;
  final List<CatalogImportProblem> problems;

  /// Whether anything is actually importable.
  ///
  /// A file whose every row is broken should not offer an "Import" button that
  /// does nothing - the shopkeeper would tap it, see "0 created", and not know
  /// whether it worked.
  bool get hasImportableRows => totalRows - errorRows > 0;

  bool get committed => status == 'COMMITTED';

  factory CatalogImportSummary.fromJson(Map<String, dynamic> json) {
    return CatalogImportSummary(
      runId: json['runId'] as int,
      filename: json['filename'] as String? ?? '',
      mode: json['mode'] as String? ?? 'IMPORT',
      status: json['status'] as String? ?? 'PREVIEWED',
      totalRows: json['totalRows'] as int? ?? 0,
      validRows: json['validRows'] as int? ?? 0,
      warningRows: json['warningRows'] as int? ?? 0,
      errorRows: json['errorRows'] as int? ?? 0,
      createdCount: json['createdCount'] as int? ?? 0,
      updatedCount: json['updatedCount'] as int? ?? 0,
      problems: ((json['problems'] as List<dynamic>?) ?? const [])
          .map((e) => CatalogImportProblem.fromJson(
              Map<String, dynamic>.from(e as Map)))
          .toList(),
    );
  }
}

class CatalogImportProblem {
  const CatalogImportProblem({
    required this.row,
    required this.field,
    required this.severity,
    required this.problem,
    required this.suggestion,
  });

  final int row;
  final String? field;
  final String severity;
  final String problem;
  final String? suggestion;

  bool get isError => severity == 'ERROR';

  factory CatalogImportProblem.fromJson(Map<String, dynamic> json) {
    return CatalogImportProblem(
      row: json['row'] as int? ?? 0,
      field: json['field'] as String?,
      severity: json['severity'] as String? ?? 'ERROR',
      problem: json['problem'] as String? ?? '',
      suggestion: json['suggestion'] as String?,
    );
  }
}

/// One past import, for the history list.
class CatalogImportRun {
  const CatalogImportRun({
    required this.id,
    required this.filename,
    required this.adminEmail,
    required this.status,
    required this.totalRows,
    required this.errorRows,
    required this.createdCount,
    required this.updatedCount,
    required this.createdAt,
  });

  final int id;
  final String filename;
  final String? adminEmail;
  final String status;
  final int totalRows;
  final int errorRows;
  final int createdCount;
  final int updatedCount;
  final DateTime? createdAt;

  factory CatalogImportRun.fromJson(Map<String, dynamic> json) {
    return CatalogImportRun(
      id: json['id'] as int,
      filename: json['filename'] as String? ?? '',
      adminEmail: json['adminEmail'] as String?,
      status: json['status'] as String? ?? '',
      totalRows: json['totalRows'] as int? ?? 0,
      errorRows: json['errorRows'] as int? ?? 0,
      createdCount: json['createdCount'] as int? ?? 0,
      updatedCount: json['updatedCount'] as int? ?? 0,
      createdAt: DateTime.tryParse(json['createdAt'] as String? ?? ''),
    );
  }
}
