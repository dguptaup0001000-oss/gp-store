import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../shared/widgets/action_feedback.dart';
import '../../products/presentation/products_providers.dart';
import '../data/demand_repository.dart';
import 'demand_providers.dart';

class INeedThisScreen extends ConsumerStatefulWidget {
  const INeedThisScreen({super.key, this.initialDescription = ''});
  final String initialDescription;

  @override
  ConsumerState<INeedThisScreen> createState() => _INeedThisScreenState();
}

class _INeedThisScreenState extends ConsumerState<INeedThisScreen> {
  late final TextEditingController _description;
  final _quantity = TextEditingController(text: '1');
  final _budget = TextEditingController();
  double _radius = 8;
  String? _mode;
  int? _categoryId;
  DateTime? _requiredBy;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _description = TextEditingController(text: widget.initialDescription);
  }

  @override
  void dispose() {
    _description.dispose();
    _quantity.dispose();
    _budget.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final pin = ref.read(deliveryPinProvider);
    if (pin == null) {
      showActionFailure(context, 'Add a delivery address before sending a local request.');
      return;
    }
    if (_description.text.trim().isEmpty) {
      showActionFailure(context, 'Describe the item or service you need.');
      return;
    }
    setState(() => _saving = true);
    try {
      final request = await ref.read(demandRepositoryProvider).create(
            description: _description.text.trim(),
            latitude: pin.lat,
            longitude: pin.lng,
            quantity: int.tryParse(_quantity.text) ?? 1,
            budget: double.tryParse(_budget.text),
            categoryId: _categoryId,
            requiredBy: _requiredBy,
            radiusKm: _radius,
            preferredMode: _mode,
          );
      ref.invalidate(myDemandRequestsProvider);
      if (!mounted) return;
      Navigator.of(context).pop(request);
    } catch (error) {
      if (mounted) showActionFailure(context, extractErrorMessage(error));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final categories = ref.watch(categoriesProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('I Need This')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text(
            'Nearby eligible shops can respond inside GP-STORE. Your exact address and contact details are never shared.',
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _description,
            maxLength: 500,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: 'What do you need?',
              hintText: 'Example: tractor brake pad for model…',
            ),
          ),
          TextField(
            controller: _quantity,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: 'Quantity'),
          ),
          TextField(
            controller: _budget,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Approximate budget (optional)'),
          ),
          const SizedBox(height: 12),
          categories.when(
            loading: () => const LinearProgressIndicator(),
            error: (_, __) => const Text(
              'Categories are unavailable. You can still send this request.',
            ),
            data: (items) => DropdownButtonFormField<int?>(
              initialValue: _categoryId,
              decoration: const InputDecoration(labelText: 'Category (optional)'),
              items: [
                const DropdownMenuItem(value: null, child: Text('Not sure')),
                for (final category in items)
                  DropdownMenuItem(
                    value: category.id,
                    child: Text(category.name, overflow: TextOverflow.ellipsis),
                  ),
              ],
              onChanged: (value) => setState(() => _categoryId = value),
            ),
          ),
          const SizedBox(height: 12),
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Required by (optional)'),
            subtitle: Text(
              _requiredBy == null
                  ? 'Request remains open for up to 7 days'
                  : MaterialLocalizations.of(context).formatMediumDate(_requiredBy!),
            ),
            trailing: _requiredBy == null
                ? const Icon(Icons.calendar_today_outlined)
                : IconButton(
                    tooltip: 'Clear required date',
                    onPressed: () => setState(() => _requiredBy = null),
                    icon: const Icon(Icons.close),
                  ),
            onTap: () async {
              final now = DateTime.now();
              final selected = await showDatePicker(
                context: context,
                initialDate: _requiredBy ?? now.add(const Duration(days: 1)),
                firstDate: now,
                lastDate: now.add(const Duration(days: 30)),
              );
              if (selected != null && mounted) {
                setState(() {
                  _requiredBy = DateTime(
                    selected.year,
                    selected.month,
                    selected.day,
                    23,
                    59,
                  );
                });
              }
            },
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<String?>(
            initialValue: _mode,
            decoration: const InputDecoration(labelText: 'Preferred way to buy'),
            items: const [
              DropdownMenuItem(value: null, child: Text('Any supported mode')),
              DropdownMenuItem(value: 'ONLINE_PURCHASE', child: Text('Buy Online')),
              DropdownMenuItem(value: 'VISIT_TO_BUY', child: Text('Visit to Buy')),
              DropdownMenuItem(value: 'SERVICE_AT_SHOP', child: Text('Service at Shop')),
            ],
            onChanged: (value) => setState(() => _mode = value),
          ),
          const SizedBox(height: 12),
          Text('Search radius: ${_radius.round()} km'),
          Slider(
            value: _radius,
            min: 2,
            max: 50,
            divisions: 24,
            label: '${_radius.round()} km',
            onChanged: (value) => setState(() => _radius = value),
          ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _saving ? null : _submit,
            child: _saving
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text('Send to eligible local shops'),
          ),
        ],
      ),
    );
  }
}

class DemandRequestCard extends StatelessWidget {
  const DemandRequestCard({
    super.key,
    required this.request,
    this.onClose,
    this.onCancel,
    this.onReportResponse,
  });
  final DemandRequest request;
  final VoidCallback? onClose;
  final VoidCallback? onCancel;
  final ValueChanged<DemandResponse>? onReportResponse;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(request.description, style: const TextStyle(fontWeight: FontWeight.w700)),
            Text('${request.status} • ${request.responses.length} responses'),
            for (final response in request.responses)
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(response.shopName),
                subtitle: Text([
                  response.status == 'AVAILABLE' ? 'Available' : 'Not available',
                  if (response.quantity != null) 'Qty ${response.quantity}',
                  if (response.readyMinutes != null)
                    'Ready in ${response.readyMinutes} min',
                  if (response.commerceMode != null)
                    response.commerceMode!.replaceAll('_', ' '),
                  if (response.note != null) response.note!,
                ].join(' • ')),
                trailing: Wrap(
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    if (response.price != null) Text('₹${response.price}'),
                    if (onReportResponse != null)
                      IconButton(
                        tooltip: 'Report or block this response',
                        onPressed: () => onReportResponse!(response),
                        icon: const Icon(Icons.flag_outlined),
                      ),
                  ],
                ),
              ),
            if (onClose != null || onCancel != null)
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  if (onCancel != null)
                    TextButton(
                      onPressed: onCancel,
                      child: const Text('Cancel request'),
                    ),
                  if (onClose != null)
                    FilledButton.tonal(
                      onPressed: onClose,
                      child: const Text('Close request'),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}
