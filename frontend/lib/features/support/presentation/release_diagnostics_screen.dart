import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../../../core/api/api_client.dart';
import '../../../core/api/api_runtime_trace.dart';
import '../../../core/api/error_messages.dart';
import '../../auth/presentation/auth_providers.dart';

/// Safe, copyable evidence for real-device release reports.
///
/// No token, request body, query value, email, phone, credential or device
/// identifier is retained or rendered here.
class ReleaseDiagnosticsScreen extends ConsumerStatefulWidget {
  const ReleaseDiagnosticsScreen({super.key});

  @override
  ConsumerState<ReleaseDiagnosticsScreen> createState() =>
      _ReleaseDiagnosticsScreenState();
}

class _ReleaseDiagnosticsScreenState
    extends ConsumerState<ReleaseDiagnosticsScreen> {
  late final Future<PackageInfo> _packageInfo = PackageInfo.fromPlatform();
  Map<String, dynamic>? _backend;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _probe();
  }

  Future<void> _probe() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final response = await ref.read(apiClientProvider).dio.get('/api/version');
      if (!mounted) return;
      setState(() {
        _backend = Map<String, dynamic>.from(response.data as Map);
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = extractErrorMessage(error);
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final client = ref.watch(apiClientProvider);
    final events = ApiRuntimeTrace.snapshot;
    return Scaffold(
      appBar: AppBar(title: const Text('Release diagnostics')),
      body: RefreshIndicator(
        onRefresh: _probe,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text(
              'Safe release identity',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 8),
            FutureBuilder<PackageInfo>(
              future: _packageInfo,
              builder: (context, snapshot) {
                final package = snapshot.data;
                return SelectableText('App: ${ApiClient.appName}\n'
                    'APK source: ${ApiClient.buildSha}\n'
                    'Version: ${package?.version ?? 'loading'}\n'
                    'Build number: ${package?.buildNumber ?? 'loading'}\n'
                    'Package: ${package?.packageName ?? 'loading'}\n'
                    'Environment: ${client.environment.name}\n'
                    'API: ${client.environment.baseUrl}');
              },
            ),
            const SizedBox(height: 20),
            const Text('Backend',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            if (_loading) const LinearProgressIndicator(minHeight: 2),
            if (_error != null) ...[
              Text(_error!),
              TextButton(onPressed: _probe, child: const Text('Retry')),
            ],
            if (_backend != null)
              SelectableText([
                'Runtime SHA: ${_backend!['gitCommit'] ?? 'unknown'}',
                'Binary SHA: ${_backend!['binaryGitCommit'] ?? 'unknown'}',
                'Schema: ${_backend!['schemaVersion'] ?? 'unknown'}',
                'Backend environment: ${_backend!['environment'] ?? 'unknown'}',
              ].join('\n')),
            const SizedBox(height: 20),
            const Text('Recent API results',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            const Text(
              'Query names are shown; values and all credentials are omitted.',
            ),
            const SizedBox(height: 8),
            if (events.isEmpty) const Text('No API results recorded yet.'),
            for (final event in events.take(20))
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: SelectableText(
                  '${event.method} ${event.url}\n'
                  'HTTP ${event.status ?? 'network error'} · '
                  'backend ${event.backendBuild ?? 'not reported'} · '
                  '${event.at.toIso8601String()}',
                ),
              ),
            const SizedBox(height: 16),
            const Text(
              'This screen never shows passwords, tokens, OTPs, activation codes, '
              'payment credentials, request bodies or personal query values.',
            ),
          ],
        ),
      ),
    );
  }
}
