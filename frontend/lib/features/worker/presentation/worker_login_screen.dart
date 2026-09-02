import 'package:flutter/material.dart';

import '../../../core/api/api_client.dart';
import '../../../core/api/error_messages.dart';
import '../../../core/storage/token_storage.dart';
import '../data/worker_repository.dart';
import '../domain/worker_models.dart';

/// Sign in, and nothing else.
///
/// NO REGISTRATION, NO PASSWORD RESET, NO OTP. These are shop employees whose
/// accounts an administrator creates; every one of those flows would be a door
/// into the shop's data that nobody needs and somebody would eventually have to
/// defend.
///
/// PHONE NUMBER OR EMAIL, whichever the rider has to hand. The shop records
/// both, both are unique, and a worker standing in the street should not have
/// to remember which one was typed into the roster.
///
/// It posts to /api/worker/auth/login, which checks the credentials the shop
/// set on the worker's own record. That is deliberately NOT the customer login:
/// a worker is no longer a customer account, so the same address can belong to
/// the shop owner, a shopper and a rider without those three colliding.
class WorkerLoginScreen extends StatefulWidget {
  const WorkerLoginScreen({
    super.key,
    required this.apiClient,
    required this.tokenStorage,
    required this.repository,
    required this.onSignedIn,
    this.initialNotice,
  });

  final ApiClient apiClient;
  final TokenStorage tokenStorage;
  final WorkerRepository repository;
  final ValueChanged<WorkerProfile> onSignedIn;

  /// Why the previous session ended, when it ended for a reason worth saying.
  ///
  /// A worker dropped here with no explanation assumes a typo and retypes the
  /// same password. "This login is not linked to a worker record" tells them
  /// it is not their password and names who can fix it.
  final String? initialNotice;

  @override
  State<WorkerLoginScreen> createState() => _WorkerLoginScreenState();
}

class _WorkerLoginScreenState extends State<WorkerLoginScreen> {
  final _identifier = TextEditingController();
  final _password = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _error = widget.initialNotice;
  }

  @override
  void dispose() {
    _identifier.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _signIn() async {
    if (_busy) return;
    final identifier = _identifier.text.trim();
    if (identifier.isEmpty) {
      setState(() {
        _error = 'Enter your phone number or email address.';
      });
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      final response = await widget.apiClient.dio.post(
        '/api/worker/auth/login',
        data: {
          'identifier': identifier,
          'password': _password.text,
        },
      );

      // READ, NOT CAST. Casting threw a TypeError on any reply that was not
      // the expected shape - a proxy's HTML error page, a body with a null
      // token - and the catch below turned that into the connection message,
      // sending a worker to check a connection that was working.
      final body = response.data;
      final accessToken =
          body is Map && body['accessToken'] is String ? body['accessToken'] as String : null;
      if (accessToken == null || accessToken.isEmpty) {
        if (!mounted) return;
        setState(() => _error =
            'Signed in, but the server\'s reply could not be read. This is a '
            'bug on our side - please tell an administrator.');
        return;
      }

      // NO REFRESH TOKEN, on purpose. A worker session lasts a shift and the
      // server re-checks the roster row on every request, so a rider who is
      // paused or removed stops working on their next tap rather than whenever
      // a token happened to expire. When the shift-long token does run out the
      // client simply asks them to sign in again, which is what it already
      // does when there is nothing to refresh with.
      //
      // Held in memory only until /api/worker/me answers, so a reply that
      // turns out not to be a usable session never reaches disk.
      widget.tokenStorage.holdTokensInMemory(
        accessToken: accessToken,
        refreshToken: '',
      );

      try {
        final profile = await widget.repository.me();
        await widget.tokenStorage.saveTokens(
          accessToken: accessToken,
          refreshToken: '',
        );
        await widget.tokenStorage.setRememberMe(true);
        if (!mounted) return;
        widget.onSignedIn(profile);
      } catch (e) {
        await widget.tokenStorage.clear();
        rethrow;
      }
    } catch (e) {
      // ONE CATCH, AND IT USES THE SERVER'S WORDS.
      //
      // This used to be `on ApiException` with a connection message as the
      // fallback, and the first clause could never match: ApiClient's
      // interceptor returns a DioException that CARRIES an ApiException in
      // its .error field - it does not throw the ApiException itself. So
      // every failure landed in the fallback, and a worker whose account was
      // simply not linked to a worker record was told to check their
      // connection. The backend's own sentence for that case -
      // "This login is not linked to a worker record. Ask an administrator
      // to link it." - names the person who can fix it, and was thrown away.
      //
      // extractErrorMessage is the helper the rest of the app already uses;
      // it unwraps that DioException, prefers the backend's message, and
      // still distinguishes offline from slow from refused.
      if (!mounted) return;
      setState(() => _error = extractErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('GP-STORE',
                    style: Theme.of(context).textTheme.headlineLarge),
                const SizedBox(height: 4),
                Text('Delivery Worker',
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        color: Theme.of(context).colorScheme.primary)),
                const SizedBox(height: 40),
                TextField(
                  controller: _identifier,
                  autocorrect: false,
                  enableSuggestions: false,
                  keyboardType: TextInputType.emailAddress,
                  textInputAction: TextInputAction.next,
                  decoration: const InputDecoration(
                    labelText: 'Phone number or email',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _password,
                  obscureText: true,
                  textInputAction: TextInputAction.done,
                  onSubmitted: (_) => _signIn(),
                  decoration: const InputDecoration(
                    labelText: 'Password',
                    border: OutlineInputBorder(),
                  ),
                ),
                if (_error != null) ...[
                  const SizedBox(height: 16),
                  Text(
                    _error!,
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ],
                const SizedBox(height: 24),
                SizedBox(
                  height: 56,
                  child: FilledButton(
                    onPressed: _busy ? null : _signIn,
                    child: _busy
                        ? const SizedBox(
                            height: 22,
                            width: 22,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('SIGN IN',
                            style: TextStyle(fontSize: 17, letterSpacing: 1)),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
