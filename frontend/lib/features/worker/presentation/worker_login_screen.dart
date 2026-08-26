import 'package:flutter/material.dart';

import '../../../core/api/api_client.dart';
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
  });

  final ApiClient apiClient;
  final TokenStorage tokenStorage;
  final WorkerRepository repository;
  final ValueChanged<WorkerProfile> onSignedIn;

  @override
  State<WorkerLoginScreen> createState() => _WorkerLoginScreenState();
}

class _WorkerLoginScreenState extends State<WorkerLoginScreen> {
  final _identifier = TextEditingController();
  final _password = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _identifier.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _signIn() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      final response = await widget.apiClient.dio.post(
        '/api/auth/login',
        data: {
          'email': _identifier.text.trim(),
          'password': _password.text,
        },
      );
      final body = Map<String, dynamic>.from(response.data as Map);
      final accessToken = body['token'] as String;
      final refreshToken = body['refreshToken'] as String;

      // Hold in memory only until /api/worker/me confirms this account is a
      // worker. Persisting first left a customer (or disabled) session on
      // disk when me() failed.
      widget.tokenStorage.holdTokensInMemory(
        accessToken: accessToken,
        refreshToken: refreshToken,
      );

      try {
        final profile = await widget.repository.me();
        await widget.tokenStorage.saveTokens(
          accessToken: accessToken,
          refreshToken: refreshToken,
        );
        await widget.tokenStorage.setRememberMe(true);
        if (!mounted) return;
        widget.onSignedIn(profile);
      } catch (e) {
        await widget.tokenStorage.clear();
        rethrow;
      }
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (_) {
      setState(() =>
          _error = 'Could not sign in. Check the connection and try again.');
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
                    labelText: 'Email or mobile number',
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
