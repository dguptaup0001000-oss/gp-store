import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../../core/api/api_client.dart';
import '../domain/worker_models.dart';

/// Everything the worker app asks the server for.
///
/// THE OFFLINE RULE, which is the only subtle thing in this file. A scan that
/// did not reach the server has NOT happened. The worker is told so in those
/// words, the scan is kept on the phone, and it is retried later. Reporting a
/// queued scan as a success would have somebody walk away from a carton that
/// nobody is accountable for - which is the exact failure this whole feature
/// exists to prevent.
class WorkerRepository {
  WorkerRepository({required this.apiClient, FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  final ApiClient apiClient;
  final FlutterSecureStorage _storage;

  static const _queueKey = 'gpstore_worker_pending_scans';

  Future<WorkerProfile> me() async {
    final response = await apiClient.dio.get('/api/worker/me');
    return WorkerProfile.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  // myOrders() - the worker's own scan history - was removed from this app
  // when the home screen stopped showing it. The brief's home screen is the
  // scan button and the active orders, and "what I did today" was neither;
  // its one number (todaysOrders) already rides along with the profile.
  //
  // GET /api/worker/orders still exists on the server and is untouched. It is
  // this CLIENT that no longer needs it - a request the app was making on
  // every home refresh, on the worst connection in the business, for a list
  // nobody was looking at.

  /// One order, reopened.
  ///
  /// The scan response already carries this, so the normal flow never calls
  /// it - this is for the second look, after the app was backgrounded or the
  /// worker tapped a task on the home list.
  Future<WorkerOrder> order(int orderId) async {
    final response = await apiClient.dio.get('/api/worker/orders/$orderId');
    return WorkerOrder.fromJson(Map<String, dynamic>.from(response.data as Map));
  }

  /// Moves a delivery to its next status.
  ///
  /// NOT QUEUED WHEN OFFLINE, and that is the important difference from a
  /// scan. A scan is a claim about the past - "I took this carton" - which
  /// stays true whenever it reaches the server. A status change is a claim
  /// about now, and the server may refuse it: the delivery may have been
  /// cancelled, or reassigned, or already moved on. Replaying it an hour
  /// later could mark something delivered that never was. So this either
  /// succeeds against the server or fails visibly, and the worker retries
  /// when they have signal.
  ///
  /// Reuses PUT /api/deliveries/{id}/status, which already checks that the
  /// delivery is assigned to the caller and now also checks that the move is
  /// legal. The app never decides either.
  Future<void> setDeliveryStatus({
    required int deliveryId,
    required String status,
  }) async {
    await apiClient.dio.put(
      '/api/deliveries/$deliveryId/status',
      queryParameters: {'status': status},
    );
  }

  /// Reports where the phone is.
  ///
  /// Fire-and-forget by design: a dropped position is replaced by the next
  /// one a minute later, and an error banner every time a worker rides
  /// through a dead spot would train them to ignore banners. Genuine
  /// refusals - a fix too vague, a position that is not a coordinate - are
  /// the server's business and it drops them; nothing here pretends they
  /// were stored.
  Future<bool> reportLocation({
    required double latitude,
    required double longitude,
    double? accuracyMeters,
  }) async {
    try {
      await apiClient.dio.put(
        '/api/delivery-partners/me/location',
        data: {
          'latitude': latitude,
          'longitude': longitude,
          if (accuracyMeters != null) 'accuracyMeters': accuracyMeters,
        },
      );
      return true;
    } on DioException catch (e) {
      debugPrint('Location update not stored: ${e.message}');
      return false;
    }
  }

  Future<void> setAvailable(bool available) async {
    await apiClient.dio.post('/api/worker/status', data: {'available': available});
  }

  /// Submits one scan.
  ///
  /// [clientRequestId] is generated per physical scan and reused on every
  /// retry of it. That is what lets the server tell a retry apart from a
  /// second scan - without it, a worker tapping twice on a bad connection
  /// would produce two records and the customer two notifications.
  Future<ScanOutcome> packScan({
    required String qrToken,
    required String clientRequestId,
  }) async {
    try {
      final response = await apiClient.dio.post(
        '/api/worker/scans/pack',
        data: {'qrToken': qrToken, 'clientRequestId': clientRequestId},
      );
      return ScanOutcome.fromJson(Map<String, dynamic>.from(response.data as Map));
    } on DioException catch (e) {
      if (_isConnectivity(e)) {
        await _enqueue(qrToken: qrToken, clientRequestId: clientRequestId);
        return ScanOutcome.offline;
      }
      rethrow;
    }
  }

  /// A failure that a later attempt could plausibly succeed at.
  ///
  /// Deliberately narrow. A 4xx means the server heard the scan and refused
  /// it, and queueing that would retry a decision that will never change while
  /// telling the worker to wait for a connection that is already there.
  bool _isConnectivity(DioException e) {
    switch (e.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.connectionError:
        return true;
      case DioExceptionType.unknown:
        // Dio reports a dead radio as `unknown` wrapping a SocketException.
        return e.error is Exception && e.response == null;
      default:
        return false;
    }
  }

  // ------------------------------------------------------------ the queue

  Future<void> _enqueue({required String qrToken, required String clientRequestId}) async {
    final pending = await pendingScans();
    // Same physical scan, retried: it is already waiting.
    if (pending.any((p) => p['clientRequestId'] == clientRequestId)) {
      return;
    }
    pending.add({
      'qrToken': qrToken,
      'clientRequestId': clientRequestId,
      'queuedAt': DateTime.now().toIso8601String(),
    });
    await _storage.write(key: _queueKey, value: jsonEncode(pending));
  }

  Future<List<Map<String, dynamic>>> pendingScans() async {
    final raw = await _storage.read(key: _queueKey);
    if (raw == null || raw.isEmpty) return [];
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return [];
      return decoded.map((e) => Map<String, dynamic>.from(e as Map)).toList();
    } catch (_) {
      // A queue we cannot read is worse than no queue: it would fail on every
      // flush forever. Drop it and say so in debug.
      debugPrint('Worker scan queue was unreadable and has been cleared.');
      await _storage.delete(key: _queueKey);
      return [];
    }
  }

  /// Sends whatever is waiting, oldest first.
  ///
  /// Stops at the first connectivity failure rather than draining blindly:
  /// if the connection is still down the rest will fail too, and hammering it
  /// costs battery on a phone that is already struggling for signal.
  ///
  /// A scan the server REFUSES is removed from the queue, not retried. The
  /// answer will not change, and a permanently stuck queue would block every
  /// later scan behind it.
  Future<List<ScanOutcome>> flushQueue() async {
    final pending = await pendingScans();
    if (pending.isEmpty) return const [];

    final results = <ScanOutcome>[];
    final remaining = <Map<String, dynamic>>[];
    var connectionDown = false;

    for (final item in pending) {
      if (connectionDown) {
        remaining.add(item);
        continue;
      }
      try {
        final response = await apiClient.dio.post(
          '/api/worker/scans/pack',
          data: {
            'qrToken': item['qrToken'],
            'clientRequestId': item['clientRequestId'],
          },
        );
        results.add(ScanOutcome.fromJson(Map<String, dynamic>.from(response.data as Map)));
      } on DioException catch (e) {
        if (_isConnectivity(e)) {
          connectionDown = true;
          remaining.add(item);
        } else {
          // Refused by the server. Surface it so the worker learns what
          // happened to a scan they were told was queued.
          final data = e.response?.data;
          results.add(ScanOutcome(
            accepted: false,
            outcome: 'REJECTED',
            // The server's own wording where there is one - a queued scan
            // that was later refused is exactly the case where the worker
            // needs to know WHY, and a generic sentence would waste the one
            // chance to tell them.
            message: data is Map && data['message'] != null
                ? data['message'].toString()
                : 'That scan was refused when it reached the server.',
          ));
        }
      }
    }

    if (remaining.isEmpty) {
      await _storage.delete(key: _queueKey);
    } else {
      await _storage.write(key: _queueKey, value: jsonEncode(remaining));
    }
    return results;
  }
}
