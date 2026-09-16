import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/api/error_messages.dart';
import '../domain/control_tower_models.dart';
import 'platform_providers.dart';
import 'platform_entity_360_screen.dart';

/// Reusable paged reader for platform operational projections. It contains no
/// write controls; sensitive actions continue through their established,
/// reasoned workflows.
class PlatformResourceScreen extends ConsumerStatefulWidget {
  const PlatformResourceScreen({
    super.key,
    required this.resource,
    required this.title,
    required this.icon,
  });

  final String resource;
  final String title;
  final IconData icon;

  @override
  ConsumerState<PlatformResourceScreen> createState() => _PlatformResourceScreenState();
}

class _PlatformResourceScreenState extends ConsumerState<PlatformResourceScreen> {
  final _query = TextEditingController();
  Timer? _debounce;
  PlatformResourcePage? _page;
  Object? _error;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _query.dispose();
    super.dispose();
  }

  Future<void> _load({int page = 0, bool append = false}) async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final next = await ref.read(platformRepositoryProvider).controlTowerResource(
            resource: widget.resource,
            query: _query.text,
            page: page,
          );
      if (!mounted) return;
      setState(() {
        if (append && _page != null) {
          _page = PlatformResourcePage(
            content: [..._page!.content, ...next.content],
            page: next.page,
            totalPages: next.totalPages,
            totalElements: next.totalElements,
          );
        } else {
          _page = next;
        }
      });
    } catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: AdminColors.background,
        appBar: AppBar(title: Text(widget.title)),
        body: RefreshIndicator(
          onRefresh: _load,
          child: ListView(
            padding: const EdgeInsets.all(AdminSpacing.lg),
            children: [
              TextField(
                controller: _query,
                onChanged: (_) {
                  _debounce?.cancel();
                  _debounce = Timer(const Duration(milliseconds: 350), _load);
                },
                decoration: InputDecoration(
                  hintText: 'Filter ${widget.title.toLowerCase()}',
                  prefixIcon: const Icon(Icons.search_rounded),
                  filled: true,
                  fillColor: AdminColors.surface,
                  border: const OutlineInputBorder(borderRadius: AdminRadius.control),
                ),
              ),
              const SizedBox(height: AdminSpacing.lg),
              if (_loading && _page == null)
                const Center(child: CircularProgressIndicator(strokeWidth: 2))
              else if (_error != null)
                AdminSectionCard(
                  child: Column(children: [
                    Text(extractErrorMessage(_error!)),
                    TextButton(onPressed: _load, child: const Text('Retry')),
                  ]),
                )
              else
                _list(),
            ],
          ),
        ),
      );

  Widget _list() {
    final page = _page;
    if (page == null || page.content.isEmpty) {
      return const AdminSectionCard(child: Text('No matching records.'));
    }
    return AdminSectionCard(
      title: '${page.totalElements} ${widget.title.toLowerCase()}',
      child: Column(children: [
        for (final row in page.content)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: CircleAvatar(
              backgroundColor: AdminColors.primaryFaint,
              child: Icon(widget.icon, color: AdminColors.primaryDark),
            ),
            title: Text(_title(row), maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(_subtitle(row), maxLines: 3, overflow: TextOverflow.ellipsis),
            trailing: const {'orders', 'customers', 'merchants', 'shops'}
                    .contains(widget.resource)
                ? const Icon(Icons.chevron_right_rounded)
                : null,
            onTap: row['id'] is num ? () => _open(row) : null,
          ),
        if (page.hasMore)
          TextButton.icon(
            onPressed: _loading ? null : () => _load(page: page.page + 1, append: true),
            icon: const Icon(Icons.expand_more_rounded),
            label: const Text('Load more'),
          ),
      ]),
    );
  }

  void _open(Map<String, dynamic> row) {
    final id = (row['id'] as num).toInt();
    if (widget.resource == 'orders') {
      Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => PlatformOrder360Screen(orderId: id),
      ));
      return;
    }
    final type = switch (widget.resource) {
      'customers' => 'CUSTOMER',
      'merchants' => 'MERCHANT',
      'shops' => 'SHOP',
      _ => null,
    };
    if (type == null) return;
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => PlatformEntity360Screen(
        result: PlatformSearchResult(
          entityType: type,
          entityId: id,
          title: row['name']?.toString() ?? '$type $id',
          reference: '${type[0]}-$id',
        ),
      ),
    ));
  }

  String _title(Map<String, dynamic> row) {
    final candidates = [
      row['orderNumber'], row['product'], row['name'], row['refundReference'],
      row['action'], row['reviewType'], row['transactionReference'],
    ].where((value) => value != null && value.toString().isNotEmpty).toList();
    return candidates.isEmpty
        ? '${widget.title} ${row['id'] ?? ''}'
        : candidates.first.toString();
  }

  String _subtitle(Map<String, dynamic> row) => row.entries
      .where((entry) => entry.value != null && !const {'id', 'orderNumber', 'product', 'name'}.contains(entry.key))
      .take(5)
      .map((entry) => '${_label(entry.key)}: ${entry.value}')
      .join(' · ');

  static String _label(String value) => value
      .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]} ${m[2]}')
      .split(' ')
      .map((part) => part.isEmpty ? part : '${part[0].toUpperCase()}${part.substring(1)}')
      .join(' ');
}

class PlatformOrder360Screen extends ConsumerStatefulWidget {
  const PlatformOrder360Screen({super.key, required this.orderId});
  final int orderId;

  @override
  ConsumerState<PlatformOrder360Screen> createState() => _PlatformOrder360ScreenState();
}

class _PlatformOrder360ScreenState extends ConsumerState<PlatformOrder360Screen> {
  late Future<Map<String, dynamic>> _future;

  @override
  void initState() {
    super.initState();
    _future = ref.read(platformRepositoryProvider).controlTowerOrder(widget.orderId);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text('Order #${widget.orderId}')),
        backgroundColor: AdminColors.background,
        body: FutureBuilder<Map<String, dynamic>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator(strokeWidth: 2));
            }
            if (snapshot.hasError) {
              return Center(child: Text(extractErrorMessage(snapshot.error!)));
            }
            final data = snapshot.data ?? const <String, dynamic>{};
            return ListView(
              padding: const EdgeInsets.all(AdminSpacing.lg),
              children: [
                AdminSectionCard(
                  title: data['orderNumber']?.toString() ?? 'Order',
                  child: _MapFacts(data, excludeCollections: true),
                ),
                for (final key in const ['items', 'payment', 'timeline'])
                  if (data[key] != null) ...[
                    const SizedBox(height: AdminSpacing.md),
                    AdminSectionCard(
                      title: PlatformResourceScreenStateLabel.label(key),
                      child: _CollectionFacts(value: data[key]),
                    ),
                  ],
              ],
            );
          },
        ),
      );
}

class _MapFacts extends StatelessWidget {
  const _MapFacts(this.data, {this.excludeCollections = false});
  final Map<String, dynamic> data;
  final bool excludeCollections;

  @override
  Widget build(BuildContext context) => Column(
        children: [
          for (final entry in data.entries)
            if (entry.value != null &&
                (!excludeCollections || (entry.value is! List && entry.value is! Map)))
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  SizedBox(
                    width: 140,
                    child: Text(PlatformResourceScreenStateLabel.label(entry.key),
                        style: AdminText.caption),
                  ),
                  Expanded(child: SelectableText(entry.value.toString())),
                ]),
              ),
        ],
      );
}

class _CollectionFacts extends StatelessWidget {
  const _CollectionFacts({required this.value});
  final dynamic value;

  @override
  Widget build(BuildContext context) {
    if (value is Map) return _MapFacts(Map<String, dynamic>.from(value as Map));
    if (value is List) {
      if ((value as List).isEmpty) return const Text('No recorded events.');
      return Column(
        children: [
          for (final item in value as List)
            if (item is Map)
              Padding(
                padding: const EdgeInsets.only(bottom: AdminSpacing.md),
                child: _MapFacts(Map<String, dynamic>.from(item)),
              ),
        ],
      );
    }
    return Text(value.toString());
  }
}

class PlatformResourceScreenStateLabel {
  static String label(String value) => value
      .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]} ${m[2]}')
      .replaceAll('_', ' ')
      .split(' ')
      .where((part) => part.isNotEmpty)
      .map((part) => '${part[0].toUpperCase()}${part.substring(1)}')
      .join(' ');
}
