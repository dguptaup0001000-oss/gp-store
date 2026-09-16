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
              if (entry.value != null && entry.value is! Map && entry.value is! List)
                _Fact(label: _label(entry.key), value: entry.value.toString()),
            if (widget.result.entityType == 'CUSTOMER' && key == 'identity')
              Align(
                alignment: Alignment.centerLeft,
                child: Wrap(spacing: 8, children: [
                  OutlinedButton(
                    onPressed: () => _reveal('email'),
                    child: const Text('Reveal email'),
                  ),
                  OutlinedButton(
                    onPressed: () => _reveal('phone'),
                    child: const Text('Reveal phone'),
                  ),
                ]),
              ),
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

  Future<void> _reveal(String field) async {
    final controller = TextEditingController();
    final reason = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Reveal customer $field'),
        content: TextField(
          controller: controller,
          minLines: 2,
          maxLines: 4,
          decoration: const InputDecoration(
            labelText: 'Operational reason (required)',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: const Text('Reveal and audit'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (reason == null || reason.length < 5 || !mounted) return;
    try {
      final value = await ref.read(platformRepositoryProvider).revealCustomerPii(
            customerId: widget.result.entityId,
            field: field,
            reason: reason,
          );
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text('Customer $field'),
          content: SelectableText(value ?? 'Not recorded'),
          actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close'))],
        ),
      );
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(extractErrorMessage(error))),
        );
      }
    }
  }

  static String _label(String value) => value
      .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]} ${m[2]}')
      .replaceAll('_', ' ')
      .split(' ')
      .where((part) => part.isNotEmpty)
      .map((part) => '${part[0].toUpperCase()}${part.substring(1)}')
      .join(' ');
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
