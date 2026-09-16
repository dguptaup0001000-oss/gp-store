import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/api/error_messages.dart';
import 'platform_providers.dart';

class PlatformSystemHealthScreen extends ConsumerStatefulWidget {
  const PlatformSystemHealthScreen({super.key});

  @override
  ConsumerState<PlatformSystemHealthScreen> createState() => _PlatformSystemHealthScreenState();
}

class _PlatformSystemHealthScreenState extends ConsumerState<PlatformSystemHealthScreen> {
  late Future<Map<String, dynamic>> _health;

  @override
  void initState() {
    super.initState();
    _health = _load();
  }

  Future<Map<String, dynamic>> _load() =>
      ref.read(platformRepositoryProvider).systemHealth();

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('System Health')),
        backgroundColor: AdminColors.background,
        body: RefreshIndicator(
          onRefresh: () async => setState(() => _health = _load()),
          child: FutureBuilder<Map<String, dynamic>>(
            future: _health,
            builder: (context, snapshot) {
              if (snapshot.connectionState == ConnectionState.waiting) {
                return const Center(child: CircularProgressIndicator(strokeWidth: 2));
              }
              if (snapshot.hasError) {
                return ListView(children: [
                  const SizedBox(height: 120),
                  Center(child: Text(extractErrorMessage(snapshot.error!))),
                ]);
              }
              final data = snapshot.data ?? const {};
              return ListView(
                padding: const EdgeInsets.all(AdminSpacing.lg),
                children: [
                  for (final entry in data.entries) ...[
                    AdminSectionCard(
                      title: entry.key == 'version' ? 'Deployment' : 'Services',
                      child: SelectableText(_pretty(entry.value)),
                    ),
                    const SizedBox(height: AdminSpacing.md),
                  ],
                  const Text(
                    'Only safe operational status is shown. Credentials, environment variables, signing keys and provider secrets are never available here.',
                    style: AdminText.bodyMuted,
                  ),
                ],
              );
            },
          ),
        ),
      );

  static String _pretty(dynamic value, [int indent = 0]) {
    if (value is Map) {
      return value.entries
          .map((entry) => '${'  ' * indent}${entry.key}: ${entry.value is Map ? '\n${_pretty(entry.value, indent + 1)}' : entry.value}')
          .join('\n');
    }
    return value.toString();
  }
}
