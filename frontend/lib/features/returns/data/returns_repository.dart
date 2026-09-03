import '../../../core/api/api_client.dart';
import '../domain/return_models.dart';

class ReturnsRepository {
  ReturnsRepository({required this.apiClient});

  final ApiClient apiClient;

  /// How many units of each order line can still be sent back.
  ///
  /// Asked of the server rather than worked out from the order, because the
  /// answer depends on returns the customer may have made from another
  /// device - and a form that offers three when one is left is a refusal the
  /// customer only discovers after filling it in.
  Future<Map<int, int>> returnableLines(int orderId) async {
    final response =
        await apiClient.dio.get('/api/returns/orders/$orderId/returnable');
    final data = response.data as Map<String, dynamic>;
    return data.map((key, value) =>
        MapEntry(int.parse(key), (value as num).toInt()));
  }

  /// Ask to send items back. [lines] is order-line id to quantity.
  Future<ReturnRequest> request({
    required int orderId,
    required Map<int, int> lines,
    String? reason,
  }) async {
    final response = await apiClient.dio.post(
      '/api/returns/orders/$orderId',
      data: {
        'lines': lines.map((k, v) => MapEntry(k.toString(), v)),
        if (reason != null && reason.trim().isNotEmpty) 'reason': reason.trim(),
      },
    );
    return ReturnRequest.fromJson(response.data as Map<String, dynamic>);
  }

  Future<List<ReturnRequest>> mine({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/returns/me',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List? ?? const [];
    return content
        .map((e) => ReturnRequest.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<void> cancel(int returnId) async {
    await apiClient.dio.post('/api/returns/$returnId/cancel');
  }

  // --- staff ---

  Future<List<ReturnRequest>> pending({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/returns/pending',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List? ?? const [];
    return content
        .map((e) => ReturnRequest.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Take the goods back. The refund amount is the server's to decide.
  Future<ReturnRequest> approve(int returnId) async {
    final response = await apiClient.dio.post('/api/returns/$returnId/approve');
    return ReturnRequest.fromJson(response.data as Map<String, dynamic>);
  }

  Future<ReturnRequest> reject(int returnId, String note) async {
    final response = await apiClient.dio.post(
      '/api/returns/$returnId/reject',
      data: {'note': note},
    );
    return ReturnRequest.fromJson(response.data as Map<String, dynamic>);
  }
}
