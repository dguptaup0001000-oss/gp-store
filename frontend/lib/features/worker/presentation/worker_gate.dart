import 'package:flutter/material.dart';

import '../../../core/api/api_client.dart';
import '../../../core/api/error_messages.dart';
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

/// What starting up decided.
///
/// Three outcomes, not two, and the third is the one that was missing: a
/// stored session the app could not VERIFY is different from a session that
/// was REFUSED, and only one of them should end with the worker signed out.
class _Startup {
  const _Startup._({this.profile, this.notice, this.unreachable = false});

  const _Startup.signedIn(WorkerProfile profile) : this._(profile: profile);
  const _Startup.signedOut({String? notice}) : this._(notice: notice);
  // Named cannotReach rather than unreachable: a named constructor and a
  // field of the same name in one class is at best confusing and at worst a
  // compile error, and this is not the place to find out which.
  const _Startup.cannotReach() : this._(unreachable: true);

  final WorkerProfile? profile;

  /// Why the stored session was dropped, when there was one to drop.
  final String? notice;

  /// A session is stored but the server could not be reached to check it.
  final bool unreachable;
}

class _WorkerGateState extends State<WorkerGate> {
  Future<_Startup>? _session;

  @override
  void initState() {
    super.initState();
    _session = _restore();
  }

  Future<_Startup> _restore() async {
    final token = await widget.tokenStorage.getAccessToken();
    if (token == null || token.isEmpty) {
      return const _Startup.signedOut();
    }
    try {
      return _Startup.signedIn(await widget.repository.me());
    } catch (e) {
      // BEING OFFLINE IS NOT BEING SIGNED OUT, and this used to treat them as
      // the same thing: any failure here cleared the tokens. A worker who
      // opened the app in a basement, a lift, or the usual dead patch on the
      // route was silently signed out and had to type a password back in on
      // the connection that had just failed them - in an app whose entire
      // premise is that the connection is unreliable.
      //
      // A refusal is different: the server heard us and said no, so the
      // session really is finished and keeping it would only fail again.
      if (isConnectivityFailure(e)) {
        return const _Startup.cannotReach();
      }
      await widget.tokenStorage.clear();

      // And say WHY. A worker whose link an administrator removed used to be
      // dropped at a login screen with no explanation, where the natural
      // conclusion is "I typed my password wrong" - so they retype it, it
      // fails the same way, and they call the shop about the wrong problem.
      // The backend's sentence for that case names who can fix it.
      final status = apiStatusOf(e);
      return _Startup.signedOut(
        notice: status == 401 ? null : extractErrorMessage(e),
      );
    }
  }

  void _retryStartup() {
    setState(() => _session = _restore());
  }

  void _signedIn(WorkerProfile profile) {
    setState(() => _session = Future.value(_Startup.signedIn(profile)));
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
    setState(() => _session = Future.value(const _Startup.signedOut()));
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<_Startup>(
      future: _session,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }

        final startup = snapshot.data ?? const _Startup.signedOut();

        if (startup.unreachable) {
          return _Unreachable(onRetry: _retryStartup, onSignOut: _signOut);
        }

        final profile = startup.profile;
        if (profile == null) {
          return WorkerLoginScreen(
            apiClient: widget.apiClient,
            tokenStorage: widget.tokenStorage,
            repository: widget.repository,
            onSignedIn: _signedIn,
            initialNotice: startup.notice,
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


/// Signed in, but the shop could not be reached to confirm it.
///
/// The session is deliberately still on the phone. Retrying costs one request
/// and is the only thing a worker standing in a dead spot can usefully do;
/// signing out would make them type a password over the connection that just
/// failed.
class _Unreachable extends StatelessWidget {
  const _Unreachable({required this.onRetry, required this.onSignOut});

  final VoidCallback onRetry;
  final Future<void> Function() onSignOut;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Icon(Icons.wifi_off, size: 72, color: Colors.white70),
                const SizedBox(height: 24),
                Text(
                  'Could not reach the shop',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 12),
                const Text(
                  'You are still signed in. Move somewhere with signal and '
                  'try again.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.white70, fontSize: 16),
                ),
                const SizedBox(height: 32),
                SizedBox(
                  height: 56,
                  child: FilledButton(
                    onPressed: onRetry,
                    child: const Text('TRY AGAIN',
                        style: TextStyle(fontSize: 17, letterSpacing: 1)),
                  ),
                ),
                const SizedBox(height: 12),
                TextButton(
                  onPressed: () async => onSignOut(),
                  child: const Text('Sign out'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
