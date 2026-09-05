import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/catalog_import_models.dart';

/// The column headings the admin screen tells a shopkeeper to use.
///
/// READ FROM THE JAVA SOURCE, not duplicated. If the two drift, the app hands
/// out a heading the importer no longer recognises - and the importer's
/// response to an unrecognised column is to ignore it, so the sheet would
/// import with a whole field silently missing rather than failing loudly.
void main() {
  final importColumnJava = File(
      '../backend/src/main/java/com/gpstore/catalog/importer/ImportColumn.java');

  test('the headings shown in the app are the backend canonical names', () {
    if (!importColumnJava.existsSync()) {
      fail('cannot find ImportColumn.java at ${importColumnJava.path}');
    }

    // Each enum constant declares its canonical heading as the FIRST string
    // argument: `SKU("SKU", "sku", "variant sku"),`. The aliases after it are
    // what the importer also accepts, not what we advertise.
    final pattern = RegExp(r'^\s{4}[A-Z][A-Z0-9_]*\("([^"]+)"', multiLine: true);
    final backend = pattern
        .allMatches(importColumnJava.readAsStringSync())
        .map((m) => m.group(1)!)
        .toList();

    expect(backend, isNotEmpty,
        reason: 'parsed no columns from ImportColumn.java');

    // Order matters as well as membership: this is the order the server's
    // downloadable template writes, and the copied heading row is meant to be
    // interchangeable with it.
    expect(catalogImportColumns, backend);
  });
}
