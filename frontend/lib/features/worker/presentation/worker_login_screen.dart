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
/// It uses the SAME /api/auth/login as everyone else. A second credential path
/// would mean a second place passwords are checked and a second place to get it
/// wrong, in exchange for nothing.
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
    final email = _identifier.text.trim();
    if (!email.contains('@') || email.contains(' ')) {
      setState(() {
        _error = 'Enter the email address for this worker account.';
      });
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      final response = await widget.apiClient.dio.post(
        '/api/auth/login',
        data: {
          'email': email,
          'password': _password.text,
        },
      );

      // PARSED, NOT CAST. `body['token'] as String` threw a TypeError on any
      // reply that was not the expected pair - a proxy's HTML error page, a
      // body with a null token - and the catch below turned that into the
      // connection message, sending a worker to check a connection that was
      // working. parseRefreshPayload already does this check for the refresh
      // endpoint, which returns the identical shape.
      final tokens = parseRefreshPayload(response.data);
      if (tokens == null) {
        if (!mounted) return;
        setState(() => _error =
            'Signed in, but the server\'s reply could not be read. This is a '
            'bug on our side - please tell an administrator.');
        return;
      }

      // Hold in memory only until /api/worker/me confirms this account is a
      // worker. Persisting first left a customer (or disabled) session on
      // disk when me() failed.
      widget.tokenStorage.holdTokensInMemory(
        accessToken: tokens.access,
        refreshToken: tokens.refresh,
      );

      try {
        final profile = await widget.repository.me();
        await widget.tokenStorage.saveTokens(
          accessToken: tokens.access,
          refreshToken: tokens.refresh,
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
                    labelText: 'Email',
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
