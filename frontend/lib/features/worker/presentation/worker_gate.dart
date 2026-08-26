import 'package:flutter/material.dart';

import '../../../core/api/api_client.dart';
import '../../../core/storage/token_storage.dart';
import '../data/worker_repository.dart';
import '../domain/worker_models.dart';
import 'worker_home_screen.dart';
import 'worker_login_screen.dart';

/// Decides, once, whether to show the login or the home screen.
///
/// NO ROUTER ON PURPOSE. The customer app uses go_router because it has deep
/// links, tabs, and thirty destinations. This app has two, and the whole point
/// is that a worker can open it and be scanning in seconds - a route table
/// would be machinery in the way of that.
///
/// THE STARTUP RULE, learned the hard way in the customer app: a stored token
/// is not the same as a valid session. Rather than trusting one and letting the
/// home screen fail with an authentication error the worker cannot act on, this
/// asks the server who they are and shows the login if the answer is nobody.
class WorkerGate extends StatefulWidget {
  const WorkerGate({
    super.key,
    required this.apiClient,
    required this.tokenStorage,
    required this.repository,
  });

  final ApiClient apiClient;
  final TokenStorage tokenStorage;
  final WorkerRepository repository;

  @override
  State<WorkerGate> createState() => _WorkerGateState();
}

class _WorkerGateState extends State<WorkerGate> {
  Future<WorkerProfile?>? _session;

  @override
  void initState() {
    super.initState();
    _session = _restore();
  }

  Future<WorkerProfile?> _restore() async {
    final token = await widget.tokenStorage.getAccessToken();
    if (token == null || token.isEmpty) {
      return null;
    }
    try {
      return await widget.repository.me();
    } catch (_) {
      // An expired or revoked session, or a login that is not linked to a
      // worker record. Either way the answer is the same and it is not an
      // error worth showing: sign in again.
      await widget.tokenStorage.clear();
      return null;
    }
  }

  void _signedIn(WorkerProfile profile) {
    setState(() => _session = Future.value(profile));
  }

  Future<void> _signOut() async {
    final refreshToken = await widget.tokenStorage.getRefreshToken();
    try {
      if (refreshToken != null && refreshToken.isNotEmpty) {
        await widget.apiClient.dio.post(
          '/api/auth/logout',
          data: {'refreshToken': refreshToken},
        );
      }
    } catch (_) {
      // Local cleanup still has to happen if the network is gone.
    }
    await widget.tokenStorage.clear();
    if (!mounted) return;
    setState(() => _session = Future.value(null));
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<WorkerProfile?>(
      future: _session,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }

        final profile = snapshot.data;
        if (profile == null) {
          return WorkerLoginScreen(
            apiClient: widget.apiClient,
            tokenStorage: widget.tokenStorage,
            repository: widget.repository,
            onSignedIn: _signedIn,
          );
        }

        return WorkerHomeScreen(
          repository: widget.repository,
          initialProfile: profile,
          onSignOut: _signOut,
        );
      },
    );
  }
}
