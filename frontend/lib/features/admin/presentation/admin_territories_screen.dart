import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/territory_models.dart';
import 'admin_providers.dart';
import 'admin_territory_forms.dart';

/// Health, zone list, territory list, and outline paste/save.
class AdminTerritoriesScreen extends ConsumerWidget {
  const AdminTerritoriesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: const Text('Territories')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(
            'Delivery is organised as named territories, not a circle around the shop. '
            'The design target is 8 main zones and 26 territories. '
            'Paste a JSON outline on each territory so dispatch can assign riders.',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: AppColors.textSecondary),
          ),
          const SizedBox(height: 16),
          const _HealthSection(),
          const SizedBox(height: 12),
          const _PointCheckSection(),
          const SizedBox(height: 12),
          const _ZonesSection(),
          const SizedBox(height: 12),
          const _SubzonesSection(),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}

class _HealthSection extends ConsumerWidget {
  const _HealthSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final healthAsync = ref.watch(territoryHealthProvider);

    return _card(
      child: healthAsync.when(
        loading: () => const Padding(
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
        ),
        error: (error, stackTrace) => _errorBlock(
          message: "Couldn't load territory status: ${extractErrorMessage(error)}",
          onRetry: () => ref.invalidate(territoryHealthProvider),
        ),
        data: (health) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Map status', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(
              health.hasProblems ? 'Needs attention' : 'Matches the design counts',
              style: TextStyle(
                fontWeight: FontWeight.w700,
                color: health.hasProblems ? AppColors.error : AppColors.success,
              ),
            ),
            const SizedBox(height: 12),
            _statRow('Main zones', '${health.zones} / ${health.expectedZones}'),
            _statRow('Territories', '${health.subzones} / ${health.expectedSubzones}'),
            _statRow('With a stored outline', '${health.subzonesWithBoundary}'),
            _statRow('With a primary rider', '${health.subzonesWithPrimaryPartner}'),
            if (health.subzones == 0) ...[
              const SizedBox(height: 8),
              const Text(
                'Nothing is configured yet. Addresses will not resolve to a territory until '
                'zones and territories exist and outlines are stored later.',
              ),
            ],
            if (health.hasProblems) ...[
              const SizedBox(height: 12),
              ...health.problems.map(
                (problem) => Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text('• $problem'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _PointCheckSection extends ConsumerStatefulWidget {
  const _PointCheckSection();

  @override
  ConsumerState<_PointCheckSection> createState() => _PointCheckSectionState();
}

class _PointCheckSectionState extends ConsumerState<_PointCheckSection> {
  final _lat = TextEditingController();
  final _lng = TextEditingController();
  TerritoryResolveResult? _result;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _lat.dispose();
    _lng.dispose();
    super.dispose();
  }

  Future<void> _check() async {
    final lat = double.tryParse(_lat.text.trim());
    final lng = double.tryParse(_lng.text.trim());
    if (lat == null || lng == null) {
      setState(() {
        _error = 'Enter latitude and longitude as numbers.';
        _result = null;
      });
      return;
    }

    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final result = await ref.read(territoryRepositoryProvider).resolvePoint(latitude: lat, longitude: lng);
      if (!mounted) return;
      setState(() => _result = result);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = extractErrorMessage(e);
        _result = null;
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Check a point', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          Text(
            'Asks the server which stored outline contains these coordinates. This is not a map.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(color: AppColors.textSecondary),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _lat,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
                  decoration: const InputDecoration(labelText: 'Latitude'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextField(
                  controller: _lng,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
                  decoration: const InputDecoration(labelText: 'Longitude'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          FilledButton(
            onPressed: _busy ? null : hapticize(_check),
            child: _busy
                ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                : const Text('Check'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: const TextStyle(color: AppColors.error)),
          ],
          if (_result != null) ...[
            const SizedBox(height: 8),
            Text(
              _result!.matches.isEmpty
                  ? 'No stored outline contains that point. Mapped territories: ${_result!.mappedTerritories}.'
                  : _result!.overlapping
                      ? 'More than one territory claims this point: ${_result!.matches.join(', ')}.'
                      : 'This point is in ${_result!.subzoneCode}. Mapped territories: ${_result!.mappedTerritories}.',
            ),
          ],
        ],
      ),
    );
  }
}

class _ZonesSection extends ConsumerWidget {
  const _ZonesSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final zonesAsync = ref.watch(territoryZonesProvider);

    return _card(
      child: zonesAsync.when(
        loading: () => const Padding(
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
        ),
        error: (error, stackTrace) => _errorBlock(
          message: "Couldn't load zones: ${extractErrorMessage(error)}",
          onRetry: () => ref.invalidate(territoryZonesProvider),
        ),
        data: (zones) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text('Main zones', style: Theme.of(context).textTheme.titleMedium)),
                TextButton(
                  onPressed: hapticize(() async {
                    final saved = await showDialog<bool>(
                      context: context,
                      builder: (_) => const AdminZoneFormDialog(),
                    );
                    if (saved == true) {
                      ref.invalidate(territoryZonesProvider);
                      ref.invalidate(territoryHealthProvider);
                    }
                  }),
                  child: const Text('Add'),
                ),
              ],
            ),
            if (zones.isEmpty)
              const Text('No main zones yet. Add Z1–Z8 here. Zones have no outline of their own.')
            else
              ...zones.map(
                (zone) => ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text('${zone.code} · ${zone.name}', style: const TextStyle(fontWeight: FontWeight.w700)),
                  subtitle: Text(zone.notes?.trim().isNotEmpty == true ? zone.notes! : 'No notes'),
                  trailing: const Icon(Icons.edit_outlined, size: 20),
                  onTap: hapticize(() async {
                    final saved = await showDialog<bool>(
                      context: context,
                      builder: (_) => AdminZoneFormDialog(zone: zone),
                    );
                    if (saved == true) {
                      ref.invalidate(territoryZonesProvider);
                      ref.invalidate(territoryHealthProvider);
                    }
                  }),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _SubzonesSection extends ConsumerWidget {
  const _SubzonesSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final subzonesAsync = ref.watch(territorySubzonesProvider);
    final zones = ref.watch(territoryZonesProvider).valueOrNull ?? const <TerritoryZone>[];
    final partners = ref.watch(adminDeliveryPartnersProvider).valueOrNull ?? const [];

    return _card(
      child: subzonesAsync.when(
        loading: () => const Padding(
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
        ),
        error: (error, stackTrace) => _errorBlock(
          message: "Couldn't load territories: ${extractErrorMessage(error)}",
          onRetry: () => ref.invalidate(territorySubzonesProvider),
        ),
        data: (subzones) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text('Territories', style: Theme.of(context).textTheme.titleMedium)),
                TextButton(
                  onPressed: hapticize(() async {
                    if (zones.isEmpty) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Add a main zone first.')),
                      );
                      return;
                    }
                    final saved = await showDialog<bool>(
                      context: context,
                      builder: (_) => AdminSubzoneFormDialog(zones: zones, partners: partners),
                    );
                    if (saved == true) {
                      ref.invalidate(territorySubzonesProvider);
                      ref.invalidate(territoryHealthProvider);
                    }
                  }),
                  child: const Text('Add'),
                ),
              ],
            ),
            if (subzones.isEmpty)
              const Text(
                'No territories yet. Add one after a main zone exists. Outlines are not drawn on this screen.',
              )
            else
              ...subzones.map((subzone) {
                final outline = switch (subzone.boundaryPresence) {
                  BoundaryPresence.stored =>
                    'Outline stored (${boundaryVertexCount(subzone.boundary)} points)',
                  BoundaryPresence.unreadable => 'Stored outline could not be read',
                  BoundaryPresence.missing => 'No outline stored yet',
                };
                final rider = subzone.primaryPartner?.name;
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                    '${subzone.code} · ${subzone.name}',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  subtitle: Text(
                    [
                      subzone.zone == null ? null : 'Zone ${subzone.zone!.code}',
                      outline,
                      rider == null ? 'No primary rider' : 'Rider: $rider',
                      'Capacity ${subzone.maxConcurrentOrders}',
                    ].whereType<String>().join('\n'),
                  ),
                  isThreeLine: true,
                  trailing: const Icon(Icons.edit_outlined, size: 20),
                  onTap: hapticize(() async {
                    if (zones.isEmpty) return;
                    final saved = await showDialog<bool>(
                      context: context,
                      builder: (_) => AdminSubzoneFormDialog(
                        zones: zones,
                        subzone: subzone,
                        partners: partners,
                      ),
                    );
                    if (saved == true) {
                      ref.invalidate(territorySubzonesProvider);
                      ref.invalidate(territoryHealthProvider);
                    }
                  }),
                );
              }),
          ],
        ),
      ),
    );
  }
}

Widget _card({required Widget child}) {
  return Container(
    width: double.infinity,
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
    child: child,
  );
}

Widget _statRow(String label, String value) {
  return Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Row(
      children: [
        Expanded(child: Text(label)),
        Text(value, style: const TextStyle(fontWeight: FontWeight.w700)),
      ],
    ),
  );
}

Widget _errorBlock({required String message, required VoidCallback onRetry}) {
  return Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(message),
      TextButton(onPressed: hapticize(onRetry), child: const Text('Retry')),
    ],
  );
}
