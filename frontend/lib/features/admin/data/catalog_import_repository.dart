import 'package:dio/dio.dart';

import '../../../core/api/api_client.dart';
import '../domain/catalog_import_models.dart';

/// Talking to the bulk catalogue importer.
///
/// THE BYTES ARE HELD, NOT RE-READ. The server stores a SHA-256 of what it
/// previewed and refuses a commit whose bytes differ - so preview and commit
/// send the SAME in-memory bytes. Re-reading the file from disk between the
/// two would risk a different result if the shopkeeper edited the sheet in
/// the meantime, which is exactly what that check exists to catch.
class CatalogImportRepository {
  CatalogImportRepository({required this.apiClient});

  final ApiClient apiClient;

  static const _base = '/api/admin/catalog/import';

  /// Check a file. Writes nothing on the server.
  Future<CatalogImportSummary> preview({
    required String filename,
    required List<int> bytes,
    required bool updateOnly,
  }) async {
    final form = FormData.fromMap({
      'file': MultipartFile.fromBytes(bytes, filename: filename),
      'mode': updateOnly ? 'UPDATE_ONLY' : 'IMPORT',
    });
    final response = await apiClient.dio.post('$_base/preview', data: form);
    return CatalogImportSummary.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }

  /// Apply a previewed file. Same bytes, or the server refuses it.
  Future<CatalogImportSummary> commit({
    required int runId,
    required String filename,
    required List<int> bytes,
  }) async {
    final form = FormData.fromMap({
      'file': MultipartFile.fromBytes(bytes, filename: filename),
    });
    final response =
        await apiClient.dio.post('$_base/$runId/commit', data: form);
    return CatalogImportSummary.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }

  /// Who imported what, newest first.
  Future<List<CatalogImportRun>> history() async {
    final response = await apiClient.dio
        .get('$_base/history', queryParameters: {'page': 0, 'size': 25});
    final body = Map<String, dynamic>.from(response.data as Map);
    final content = (body['content'] as List<dynamic>?) ?? const [];
    return content
        .map((e) => CatalogImportRun.fromJson(Map<String, dynamic>.from(e as Map)))
        .toList();
  }
}
