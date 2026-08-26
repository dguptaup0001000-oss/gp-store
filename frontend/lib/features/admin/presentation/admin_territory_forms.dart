import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../domain/delivery_partner_models.dart';
import '../domain/territory_models.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminZoneFormDialog extends ConsumerStatefulWidget {
  const AdminZoneFormDialog({super.key, this.zone});

  final TerritoryZone? zone;

  @override
  ConsumerState<AdminZoneFormDialog> createState() => _AdminZoneFormDialogState();
}

class _AdminZoneFormDialogState extends ConsumerState<AdminZoneFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _code;
  late final TextEditingController _name;
  late final TextEditingController _notes;
  late final TextEditingController _order;
  late bool _active;
  bool _isSaving = false;

  bool get _isEditing => widget.zone?.id != null;

  @override
  void initState() {
    super.initState();
    final z = widget.zone;
    _code = TextEditingController(text: z?.code ?? '');
    _name = TextEditingController(text: z?.name ?? '');
    _notes = TextEditingController(text: z?.notes ?? '');
    _order = TextEditingController(text: z?.displayOrder?.toString() ?? '');
    _active = z?.active ?? true;
  }

  @override
  void dispose() {
    _code.dispose();
    _name.dispose();
    _notes.dispose();
    _order.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _isSaving = true);
    try {
      await ref.read(territoryRepositoryProvider).saveZone(
            TerritoryZone(
              id: widget.zone?.id,
              code: _code.text.trim().toUpperCase(),
              name: _name.text.trim(),
              notes: _notes.text.trim().isEmpty ? null : _notes.text.trim(),
              displayOrder: int.tryParse(_order.text.trim()),
              active: _active,
            ),
          );
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isEditing ? 'Edit zone' : 'Add zone'),
      content: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _code,
                textCapitalization: TextCapitalization.characters,
                decoration: const InputDecoration(labelText: 'Code', helperText: 'Z1 through Z8.'),
                validator: (value) => (value == null || value.trim().isEmpty) ? 'Enter a code' : null,
              ),
              TextFormField(
                controller: _name,
                decoration: const InputDecoration(labelText: 'Name'),
                validator: (value) => (value == null || value.trim().isEmpty) ? 'Enter a name' : null,
              ),
              TextFormField(
                controller: _notes,
                decoration: const InputDecoration(
                  labelText: 'Why this zone is drawn here',
                  helperMaxLines: 3,
                  helperText: 'The reason for the boundary, not a map.',
                ),
                maxLines: 3,
              ),
              TextFormField(
                controller: _order,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Display order (optional)'),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Active'),
                value: _active,
                onChanged: hapticizeValue((value) => setState(() => _active = value)),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _isSaving ? null : hapticize(() => Navigator.pop(context)), child: const Text('Cancel')),
        FilledButton(
          onPressed: _isSaving ? null : hapticize(_save),
          child: _isSaving
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}

class AdminSubzoneFormDialog extends ConsumerStatefulWidget {
  const AdminSubzoneFormDialog({super.key, required this.zones, this.subzone, this.partners = const []});

  final List<TerritoryZone> zones;
  final TerritorySubzone? subzone;
  final List<DeliveryPartnerModel> partners;

  @override
  ConsumerState<AdminSubzoneFormDialog> createState() => _AdminSubzoneFormDialogState();
}

class _AdminSubzoneFormDialogState extends ConsumerState<AdminSubzoneFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _code;
  late final TextEditingController _name;
  late final TextEditingController _notes;
  late final TextEditingController _capacity;
  late final TextEditingController _boundary;
  late int? _zoneId;
  late int? _partnerId;
  late bool _active;
  bool _isSaving = false;

  bool get _isEditing => widget.subzone?.id != null;

  @override
  void initState() {
    super.initState();
    final s = widget.subzone;
    _code = TextEditingController(text: s?.code ?? '');
    _name = TextEditingController(text: s?.name ?? '');
    _notes = TextEditingController(text: s?.notes ?? '');
    _capacity = TextEditingController(text: (s?.maxConcurrentOrders ?? 12).toString());
    _boundary = TextEditingController(text: s?.boundary ?? '');
    _zoneId = s?.zone?.id ?? (widget.zones.length == 1 ? widget.zones.first.id : null);
    _partnerId = s?.primaryPartner?.id;
    _active = s?.active ?? true;
  }

  @override
  void dispose() {
    _code.dispose();
    _name.dispose();
    _notes.dispose();
    _capacity.dispose();
    _boundary.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    final zoneId = _zoneId;
    if (zoneId == null) return;

    setState(() => _isSaving = true);
    try {
      final repo = ref.read(territoryRepositoryProvider);
      final saved = await repo.saveSubzone(
        zoneId: zoneId,
        subzone: TerritorySubzone(
          id: widget.subzone?.id,
          code: _code.text.trim().toUpperCase(),
          name: _name.text.trim(),
          boundary: _boundary.text.trim().isEmpty
              ? widget.subzone?.boundary
              : _boundary.text.trim(),
          maxConcurrentOrders: int.parse(_capacity.text.trim()),
          notes: _notes.text.trim().isEmpty ? null : _notes.text.trim(),
          displayOrder: widget.subzone?.displayOrder,
          active: _active,
        ),
      );
      final subzoneId = saved.id;
      if (subzoneId != null) {
        await repo.setPrimaryPartner(subzoneId: subzoneId, partnerId: _partnerId);
      }
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final zones = widget.zones.where((z) => z.id != null).toList();
    final partners = widget.partners.where((p) => p.id != null).toList();

    return AlertDialog(
      title: Text(_isEditing ? 'Edit territory' : 'Add territory'),
      content: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<int>(
                initialValue: zones.any((z) => z.id == _zoneId) ? _zoneId : null,
                decoration: const InputDecoration(labelText: 'Main zone'),
                items: zones
                    .map((z) => DropdownMenuItem(value: z.id, child: Text('${z.code} · ${z.name}')))
                    .toList(),
                onChanged: hapticizeValue((value) => setState(() => _zoneId = value)),
                validator: (value) => value == null ? 'Pick a zone' : null,
              ),
              TextFormField(
                controller: _code,
                textCapitalization: TextCapitalization.characters,
                decoration: const InputDecoration(labelText: 'Code', helperText: 'Z7B and so on.'),
                validator: (value) => (value == null || value.trim().isEmpty) ? 'Enter a code' : null,
              ),
              TextFormField(
                controller: _name,
                decoration: const InputDecoration(labelText: 'Name'),
                validator: (value) => (value == null || value.trim().isEmpty) ? 'Enter a name' : null,
              ),
              TextFormField(
                controller: _capacity,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: 'Max live orders for the primary rider',
                  helperText: 'Default 12. Per territory, not a shop-wide radius.',
                  helperMaxLines: 2,
                ),
                validator: (value) {
                  final n = int.tryParse(value?.trim() ?? '');
                  if (n == null || n < 1) return 'Enter a whole number of at least 1';
                  return null;
                },
              ),
              TextFormField(
                controller: _notes,
                decoration: const InputDecoration(labelText: 'Notes'),
                maxLines: 3,
              ),
              DropdownButtonFormField<int?>(
                initialValue: partners.any((p) => p.id == _partnerId) ? _partnerId : null,
                decoration: const InputDecoration(labelText: 'Primary rider'),
                items: [
                  const DropdownMenuItem<int?>(value: null, child: Text('None yet')),
                  ...partners.map((p) => DropdownMenuItem<int?>(value: p.id, child: Text(p.name))),
                ],
                onChanged: hapticizeValue((value) => setState(() => _partnerId = value)),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Active'),
                value: _active,
                onChanged: hapticizeValue((value) => setState(() => _active = value)),
              ),
              TextFormField(
                controller: _boundary,
                decoration: const InputDecoration(
                  labelText: 'Outline (JSON)',
                  helperText:
                      'Paste [[latitude, longitude], ...] with at least three points. '
                      'This is how dispatch learns the territory. Leave blank to keep the stored outline.',
                  helperMaxLines: 4,
                ),
                maxLines: 6,
                validator: (value) {
                  final text = value?.trim() ?? '';
                  if (text.isEmpty) return null;
                  return describeBoundary(text) == BoundaryPresence.unreadable
                      ? 'Not a JSON array of at least three [lat, lng] pairs'
                      : null;
                },
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _isSaving ? null : hapticize(() => Navigator.pop(context)), child: const Text('Cancel')),
        FilledButton(
          onPressed: _isSaving ? null : hapticize(_save),
          child: _isSaving
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}
