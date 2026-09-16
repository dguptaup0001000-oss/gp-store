import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/api/error_messages.dart';
import '../domain/control_tower_models.dart';
import 'platform_providers.dart';

class PlatformEntity360Screen extends ConsumerStatefulWidget {
  const PlatformEntity360Screen({super.key, required this.result});
  final PlatformSearchResult result;

  @override
  ConsumerState<PlatformEntity360Screen> createState() => _PlatformEntity360ScreenState();
}

class _PlatformEntity360ScreenState extends ConsumerState<PlatformEntity360Screen> {
  late Future<Map<String, dynamic>> _detail;

  @override
  void initState() {
    super.initState();
    _detail = _load();
  }

  Future<Map<String, dynamic>> _load() => ref.read(platformRepositoryProvider).controlTowerEntity(
        entityType: widget.result.entityType,
        entityId: widget.result.entityId,
      );

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: AdminColors.background,
        appBar: AppBar(title: Text('${widget.result.entityType} 360°')),
        body: FutureBuilder<Map<String, dynamic>>(
          future: _detail,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator(strokeWidth: 2));
            }
            if (snapshot.hasError) {
              return Center(
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Text(extractErrorMessage(snapshot.error!)),
                  TextButton(
                    onPressed: () => setState(() => _detail = _load()),
                    child: const Text('Retry'),
                  ),
                ]),
              );
            }
            return _body(snapshot.data ?? const {});
          },
        ),
      );

  Widget _body(Map<String, dynamic> data) {
    return ListView(
      padding: const EdgeInsets.all(AdminSpacing.lg),
      children: [
        for (final entry in data.entries)
          if (entry.value != null) ...[
            _section(entry.key, entry.value),
            const SizedBox(height: AdminSpacing.md),
          ],
      ],
    );
  }

  Widget _section(String key, dynamic value) {
    if (value is Map) {
      final map = Map<String, dynamic>.from(value);
      return AdminSectionCard(
        title: _label(key),
        child: Column(
          children: [
            for (final entry in map.entries)
              if (entry.value != null &&
                  entry.value is! Map &&
                  entry.value is! List &&
                  !(entry.key == 'maskedEmail' && map.containsKey('email')) &&
                  !(entry.key == 'maskedPhone' && map.containsKey('phone')))
                _Fact(label: _label(entry.key), value: entry.value.toString()),
          ],
        ),
      );
    }
    if (value is List) {
      return AdminSectionCard(
        title: _label(key),
        child: value.isEmpty
            ? const Text('No records.')
            : Column(
                children: [
                  for (final row in value.take(50))
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(row is Map
                          ? (row['name'] ?? row['orderNumber'] ?? row['shopRef'] ?? 'Record').toString()
                          : row.toString()),
                      subtitle: row is Map
                          ? Text(Map<String, dynamic>.from(row)
                              .entries
                              .where((e) => e.value != null)
                              .take(4)
                              .map((e) => '${_label(e.key)}: ${e.value}')
                              .join(' · '))
                          : null,
                    ),
                ],
              ),
      );
    }
    return AdminSectionCard(
      child: _Fact(label: _label(key), value: value.toString()),
    );
  }

  static String _label(String value) {
    // The server keeps these two legacy keys so older Super Admin builds do
    // not break, but their values are complete operational contact data now.
    if (value == 'maskedEmail') return 'Email';
    if (value == 'maskedPhone') return 'Phone';
    return value
        .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]} ${m[2]}')
        .replaceAll('_', ' ')
        .split(' ')
        .where((part) => part.isNotEmpty)
        .map((part) => '${part[0].toUpperCase()}${part.substring(1)}')
        .join(' ');
  }
}

class _Fact extends StatelessWidget {
  const _Fact({required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(width: 150, child: Text(label, style: AdminText.caption)),
          Expanded(child: SelectableText(value, style: AdminText.body)),
        ]),
      );
}
