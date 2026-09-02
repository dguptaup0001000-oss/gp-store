import '../../../core/api/api_client.dart';
import '../domain/worker_models.dart';

/// The shop's roster, over /api/admin/workers.
///
/// SEPARATE FROM AdminProductsRepository on purpose. Worker credentials are
/// their own concern now, and mixing them into the catalogue repository is how
/// they ended up entangled with customer accounts the first time.
class AdminWorkersRepository {
  AdminWorkersRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<AdminWorker>> list() async {
    final response = await apiClient.dio.get('/api/admin/workers');
    return (response.data as List)
        .map((e) => AdminWorker.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Hires someone. Email and password are required; the rest is detail.
  Future<AdminWorker> create({
    required String name,
    required String loginEmail,
    required String password,
    String? mobile,
    String? vehicleType,
    String? vehicleNumber,
    bool available = true,
  }) async {
    final response = await apiClient.dio.post('/api/admin/workers', data: {
      'name': name,
      'loginEmail': loginEmail,
      'password': password,
      'mobile': mobile,
      'vehicleType': vehicleType,
      'vehicleNumber': vehicleNumber,
      'available': available,
    });
    return AdminWorker.fromJson(response.data as Map<String, dynamic>);
  }

  /// Edits someone.
  ///
  /// A BLANK PASSWORD MEANS "LEAVE IT ALONE", and the server decides that -
  /// not this client. Changing a vehicle must never silently reset a rider's
  /// password, because the shop would not find out until they phoned.
  Future<AdminWorker> update(
    int id, {
    required String name,
    required String loginEmail,
    String password = '',
    String? mobile,
    String? vehicleType,
    String? vehicleNumber,
    bool available = true,
  }) async {
    final response = await apiClient.dio.put('/api/admin/workers/$id', data: {
      'name': name,
      'loginEmail': loginEmail,
      'password': password,
      'mobile': mobile,
      'vehicleType': vehicleType,
      'vehicleNumber': vehicleNumber,
      'available': available,
    });
    return AdminWorker.fromJson(response.data as Map<String, dynamic>);
  }

  /// Closes their access for a while. Minutes, so an hour and a day are the
  /// same call with a different number.
  Future<AdminWorker> suspend(int id, {required int minutes, String? reason}) async {
    final response = await apiClient.dio.post(
      '/api/admin/workers/$id/suspend',
      data: {'minutes': minutes, 'reason': reason},
    );
    return AdminWorker.fromJson(response.data as Map<String, dynamic>);
  }

  /// Ends a pause early.
  Future<AdminWorker> resume(int id) async {
    final response = await apiClient.dio.post('/api/admin/workers/$id/resume');
    return AdminWorker.fromJson(response.data as Map<String, dynamic>);
  }

  /// Removes them from the roster and blocks their login. Past deliveries keep
  /// showing who made them.
  Future<void> remove(int id) async {
    await apiClient.dio.delete('/api/admin/workers/$id');
  }
}
