import '../../../core/api/api_client.dart';

class DemandRepository {
  const DemandRepository({required this.apiClient});
  final ApiClient apiClient;

  Future<DemandRequest> create({
    required String description,
    required double latitude,
    required double longitude,
    int quantity = 1,
    double radiusKm = 8,
    double? budget,
    String? preferredMode,
  }) async {
    final response = await apiClient.dio.post(
      '/api/demand-requests',
      data: {
        'description': description,
        'quantity': quantity,
        'latitude': latitude,
        'longitude': longitude,
        'radiusKm': radiusKm,
        if (budget != null) 'budget': budget,
        if (preferredMode != null) 'preferredMode': preferredMode,
      },
    );
    return DemandRequest.fromJson(response.data as Map<String, dynamic>);
  }

  Future<List<DemandRequest>> mine({int page = 0}) async {
    final response = await apiClient.dio.get(
      '/api/demand-requests/mine',
      queryParameters: {'page': page, 'size': 20},
    );
    return (response.data as List)
        .map((row) => DemandRequest.fromJson(row as Map<String, dynamic>))
        .toList();
  }

  Future<DemandRequest> close(int id) async {
    final response = await apiClient.dio.post('/api/demand-requests/$id/close');
    return DemandRequest.fromJson(response.data as Map<String, dynamic>);
  }
}

class DemandRequest {
  const DemandRequest({
    required this.id,
    required this.description,
    required this.quantity,
    required this.status,
    required this.expiresAt,
    required this.responses,
  });

  final int id;
  final String description;
  final int quantity;
  final String status;
  final DateTime? expiresAt;
  final List<DemandResponse> responses;

  factory DemandRequest.fromJson(Map<String, dynamic> json) => DemandRequest(
        id: (json['id'] as num).toInt(),
        description: json['description'] as String? ?? '',
        quantity: (json['quantity'] as num?)?.toInt() ?? 1,
        status: json['status'] as String? ?? 'OPEN',
        expiresAt: DateTime.tryParse(json['expiresAt'] as String? ?? ''),
        responses: (json['responses'] as List? ?? const [])
            .map((row) => DemandResponse.fromJson(row as Map<String, dynamic>))
            .toList(),
      );
}

class DemandResponse {
  const DemandResponse({
    required this.shopName,
    required this.status,
    required this.price,
    required this.quantity,
    required this.readyMinutes,
    required this.commerceMode,
    required this.note,
  });

  final String shopName;
  final String status;
  final double? price;
  final int? quantity;
  final int? readyMinutes;
  final String? commerceMode;
  final String? note;

  factory DemandResponse.fromJson(Map<String, dynamic> json) => DemandResponse(
        shopName: json['shopName'] as String? ?? 'Local shop',
        status: json['status'] as String? ?? '',
        price: (json['price'] as num?)?.toDouble(),
        quantity: (json['quantity'] as num?)?.toInt(),
        readyMinutes: (json['readyMinutes'] as num?)?.toInt(),
        commerceMode: json['commerceMode'] as String?,
        note: json['note'] as String?,
      );
}
