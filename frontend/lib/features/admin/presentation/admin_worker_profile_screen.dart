import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../domain/worker_models.dart';
import 'admin_providers.dart';
import 'platform_providers.dart';

class AdminWorkerProfileScreen extends ConsumerStatefulWidget {
  const AdminWorkerProfileScreen({super.key, required this.workerId, this.platformScope = false});
  final int workerId;
  final bool platformScope;

  @override
  ConsumerState<AdminWorkerProfileScreen> createState() => _AdminWorkerProfileScreenState();
}

class _AdminWorkerProfileScreenState extends ConsumerState<AdminWorkerProfileScreen> {
  AdminWorkerProfile? _profile;
  Object? _error;
  bool _loading = true;
  bool _loadingMore = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({int page = 0, bool append = false}) async {
    setState(() { if (append) { _loadingMore = true; } else { _loading = true; _error = null; } });
    try {
      final next = widget.platformScope
          ? await ref.read(platformRepositoryProvider)
              .workerProfile(widget.workerId, page: page)
          : await ref.read(adminWorkersRepositoryProvider)
              .profile(widget.workerId, page: page);
      if (!mounted) return;
      setState(() {
        _profile = append && _profile != null
            ? AdminWorkerProfile(worker: next.worker, shopId: next.shopId, shopName: next.shopName,
                totalAssigned: next.totalAssigned,
                completed: next.completed, active: next.active, exceptions: next.exceptions,
                page: next.page, size: next.size, hasNext: next.hasNext,
                currentWork: next.currentWork, history: [..._profile!.history, ...next.history])
            : next;
        _loading = false;
        _loadingMore = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() { _error = error; _loading = false; _loadingMore = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final profile = _profile;
    return Scaffold(
      appBar: AppBar(title: const Text('Worker 360')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(
                  mainAxisSize: MainAxisSize.min, children: [
                    Text(extractErrorMessage(_error!), textAlign: TextAlign.center),
                    const SizedBox(height: 12),
                    FilledButton(onPressed: _load, child: const Text('Try again')),
                  ])))
              : profile == null
                  ? const Center(child: Text('Worker details unavailable'))
                  : RefreshIndicator(onRefresh: _load, child: ListView(
                      padding: const EdgeInsets.all(16),
                      children: [
                        _section('OVERVIEW', [
                          ListTile(contentPadding: EdgeInsets.zero,
                            leading: const CircleAvatar(child: Icon(Icons.person_outline)),
                            title: Text(profile.worker.name), subtitle: Text('Worker #${profile.worker.id}')),
                          _detail('Phone', _maskedPhone(profile.worker.mobile)),
                          _detail('Email', profile.worker.loginEmail),
                          _detail('Assigned shop', profile.shopName),
                          _detail('Shop ID', profile.shopId?.toString()),
                          _detail('Role', 'Delivery worker'),
                          _detail('Status', profile.worker.active ? 'Active' : 'Inactive'),
                          _detail('Availability', profile.worker.available ? 'Available' : 'Unavailable'),
                          _detail('Vehicle', _vehicle(profile.worker.vehicleType)),
                          _detail('Vehicle details', profile.worker.vehicleNumber),
                        ]),
                        const SizedBox(height: 12),
                        _section('DELIVERY OPERATIONS', [
                          Wrap(spacing: 8, runSpacing: 8, children: [
                            _metric('Assigned', profile.totalAssigned), _metric('Completed', profile.completed),
                            _metric('Active', profile.active), _metric('Exceptions', profile.exceptions),
                          ]),
                        ]),
                        const SizedBox(height: 12),
                        _deliverySection('CURRENT WORK', profile.currentWork),
                        const SizedBox(height: 12),
                        _deliverySection('DELIVERY HISTORY', profile.history),
                        if (profile.hasNext) Padding(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          child: OutlinedButton(
                            onPressed: _loadingMore ? null : () => _load(page: profile.page + 1, append: true),
                            child: Text(_loadingMore ? 'Loading…' : 'Load more history'),
                          ),
                        ),
                      ],
                    )),
    );
  }

  static Widget _section(String title, List<Widget> children) => Card(
    child: Padding(padding: const EdgeInsets.all(16), child: Column(
      crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: const TextStyle(fontWeight: FontWeight.bold, letterSpacing: .5)),
        const SizedBox(height: 10), ...children,
      ],
    )),
  );

  static Widget _detail(String label, String? value) => value == null || value.isEmpty
      ? const SizedBox.shrink()
      : Padding(padding: const EdgeInsets.symmetric(vertical: 3), child: Row(children: [
          SizedBox(width: 124, child: Text(label, style: const TextStyle(color: Colors.grey))),
          Expanded(child: Text(value)),
        ]));

  static Widget _metric(String label, int count) => Chip(label: Text('$label: $count'));

  static Widget _deliverySection(String title, List<AdminWorkerDelivery> rows) => _section(title,
      rows.isEmpty ? [const Text('No deliveries recorded.')] : rows.map((delivery) => ListTile(
        contentPadding: EdgeInsets.zero,
        leading: const Icon(Icons.local_shipping_outlined),
        title: Text(delivery.orderNumber?.isNotEmpty == true ? delivery.orderNumber! : 'Delivery #${delivery.id}'),
        subtitle: Text([delivery.status, _date(delivery.assignedAt)]
            .whereType<String>().where((v) => v.isNotEmpty).join(' · ')),
      )).toList());

  static String? _date(DateTime? date) => date == null ? null : '${date.day}/${date.month}/${date.year}';
  static String? _maskedPhone(String? value) {
    if (value == null || value.isEmpty) return value;
    final digits = value.replaceAll(RegExp(r'\D'), '');
    if (digits.length <= 4) return '••••';
    return '••••••${digits.substring(digits.length - 4)}';
  }
  static String _vehicle(String? value) => switch (value?.toUpperCase()) {
    'BIKE' => 'Bike', 'SCOOTER' => 'Scooter', 'CYCLE' => 'Cycle', 'VAN' => 'Van',
    null || '' => 'Not provided', _ => value!,
  };
}
