import 'package:dio/dio.dart';

/// A small, device-local record of safe HTTP facts needed to prove which
/// release path an installed APK actually used. It never stores bodies,
/// authorization headers, tokens, query values, emails, phones or passwords.
class ApiRuntimeEvent {
  const ApiRuntimeEvent({
    required this.method,
    required this.url,
    required this.status,
    required this.backendBuild,
    required this.at,
  });

  final String method;
  final String url;
  final int? status;
  final String? backendBuild;
  final DateTime at;
}

class ApiRuntimeTrace {
  const ApiRuntimeTrace._();

  static final List<ApiRuntimeEvent> _events = <ApiRuntimeEvent>[];

  static List<ApiRuntimeEvent> get snapshot =>
      List<ApiRuntimeEvent>.unmodifiable(_events.reversed);

  static void response(Response<dynamic> response) {
    _record(response.requestOptions, response.statusCode,
        response.headers.value('x-gp-store-backend-build'));
  }

  static void error(DioException error) {
    _record(error.requestOptions, error.response?.statusCode,
        error.response?.headers.value('x-gp-store-backend-build'));
  }

  static void _record(RequestOptions request, int? status, String? backendBuild) {
    final keys = request.queryParameters.keys.toList()..sort();
    final query = keys.isEmpty ? '' : '?${keys.join('&')}';
    _events.add(ApiRuntimeEvent(
      method: request.method,
      url: '${request.baseUrl}${request.path}$query',
      status: status,
      backendBuild: backendBuild,
      at: DateTime.now().toUtc(),
    ));
    if (_events.length > 30) {
      _events.removeRange(0, _events.length - 30);
    }
  }
}
