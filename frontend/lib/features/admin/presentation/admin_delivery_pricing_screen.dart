import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/delivery_pricing_models.dart';
import 'admin_providers.dart';

/// Shop-wide delivery price rules. Numbers are saved as-is; the server
/// applies them on checkout and normalises blank/negative values.
class AdminDeliveryPricingScreen extends ConsumerWidget {
  const AdminDeliveryPricingScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settingsAsync = ref.watch(deliveryPricingSettingsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Delivery Pricing')),
      body: settingsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  "Couldn't load delivery pricing: ${extractErrorMessage(error)}",
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 12),
                TextButton(
                  onPressed: hapticize(() => ref.invalidate(deliveryPricingSettingsProvider)),
                  child: const Text('Retry'),
                ),
              ],
            ),
          ),
        ),
        data: (settings) => _DeliveryPricingForm(
          key: ValueKey('${settings.updatedAt}-${settings.updatedBy}'),
          initial: settings,
        ),
      ),
    );
  }
}

class _DeliveryPricingForm extends ConsumerStatefulWidget {
  const _DeliveryPricingForm({super.key, required this.initial});

  final DeliveryPricingSettings initial;

  @override
  ConsumerState<_DeliveryPricingForm> createState() => _DeliveryPricingFormState();
}

class _DeliveryPricingFormState extends ConsumerState<_DeliveryPricingForm> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _tier1Charge;
  late final TextEditingController _tier1MaxKm;
  late final TextEditingController _tier2Charge;
  late final TextEditingController _tier2MaxKm;
  late final TextEditingController _additionalKm;
  late final TextEditingController _freeWeight;
  late final TextEditingController _weightPerKg;
  late final TextEditingController _maxWeightSurcharge;
  late final TextEditingController _freeMultiplier;
  late final TextEditingController _roadFactor;
  late final TextEditingController _assumedWeight;
  bool _isSaving = false;

  @override
  void initState() {
    super.initState();
    final s = widget.initial;
    _tier1Charge = TextEditingController(text: _num(s.distanceTier1Charge));
    _tier1MaxKm = TextEditingController(text: _num(s.distanceTier1MaxKm));
    _tier2Charge = TextEditingController(text: _num(s.distanceTier2Charge));
    _tier2MaxKm = TextEditingController(text: _num(s.distanceTier2MaxKm));
    _additionalKm = TextEditingController(text: _num(s.additionalKmCharge));
    _freeWeight = TextEditingController(text: _num(s.freeWeightKg));
    _weightPerKg = TextEditingController(text: _num(s.additionalWeightPerKg));
    _maxWeightSurcharge = TextEditingController(text: _num(s.maximumWeightSurcharge));
    _freeMultiplier = TextEditingController(text: _num(s.freeDeliveryMultiplier));
    _roadFactor = TextEditingController(text: _num(s.roadDistanceFactor));
    _assumedWeight = TextEditingController(text: _num(s.assumedWeightPerItemKg));
  }

  @override
  void dispose() {
    _tier1Charge.dispose();
    _tier1MaxKm.dispose();
    _tier2Charge.dispose();
    _tier2MaxKm.dispose();
    _additionalKm.dispose();
    _freeWeight.dispose();
    _weightPerKg.dispose();
    _maxWeightSurcharge.dispose();
    _freeMultiplier.dispose();
    _roadFactor.dispose();
    _assumedWeight.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSaving = true);

    try {
      await ref.read(deliveryPricingRepositoryProvider).saveSettings(
            DeliveryPricingSettings(
              id: widget.initial.id ?? 1,
              distanceTier1Charge: _parse(_tier1Charge),
              distanceTier1MaxKm: _parse(_tier1MaxKm),
              distanceTier2Charge: _parse(_tier2Charge),
              distanceTier2MaxKm: _parse(_tier2MaxKm),
              additionalKmCharge: _parse(_additionalKm),
              freeWeightKg: _parse(_freeWeight),
              additionalWeightPerKg: _parse(_weightPerKg),
              maximumWeightSurcharge: _parse(_maxWeightSurcharge),
              freeDeliveryMultiplier: _parse(_freeMultiplier),
              roadDistanceFactor: _parse(_roadFactor),
              assumedWeightPerItemKg: _parse(_assumedWeight),
            ),
          );
      ref.invalidate(deliveryPricingSettingsProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Delivery pricing saved. New checkouts use these numbers.')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Form(
      key: _formKey,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(
            'These numbers are applied by the server when a customer checks out. '
            'This screen does not calculate a delivery quote.',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: AppColors.textSecondary),
          ),
          if (widget.initial.updatedAt != null) ...[
            const SizedBox(height: 8),
            Text(
              widget.initial.updatedBy == null
                  ? 'Last saved ${widget.initial.updatedAt}'
                  : 'Last saved ${widget.initial.updatedAt} by ${widget.initial.updatedBy}',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(color: AppColors.textSecondary),
            ),
          ],
          const SizedBox(height: 16),
          _section(
            title: 'Distance',
            children: [
              _field(_tier1Charge, label: 'Charge up to the first band (₹)', helper: 'Default ₹5 for the first kilometre.'),
              _field(_tier1MaxKm, label: 'First band ends at (km)', helper: 'Default 1 km.'),
              _field(_tier2Charge, label: 'Charge up to the second band (₹)', helper: 'Default ₹10 up to 2 km.'),
              _field(_tier2MaxKm, label: 'Second band ends at (km)', helper: 'Must be farther than the first band.'),
              _field(_additionalKm, label: 'Charge per extra km after that (₹)', helper: 'Default ₹5 per extra kilometre, rounded up.'),
            ],
          ),
          const SizedBox(height: 12),
          _section(
            title: 'Weight',
            children: [
              _field(_freeWeight, label: 'Weight included with the distance charge (kg)', helper: 'Default 10 kg with no extra fee.'),
              _field(_weightPerKg, label: 'Extra charge per kg above that (₹)', helper: 'Default ₹2 per extra kilogram.'),
              _field(_maxWeightSurcharge, label: 'Maximum extra charge for weight (₹)', helper: 'Default ₹20 cap.'),
            ],
          ),
          const SizedBox(height: 12),
          _section(
            title: 'Free delivery',
            children: [
              _field(
                _freeMultiplier,
                label: 'Profit must be this many times the normal delivery charge',
                helper: 'Default 3. Free delivery is decided on the server from stored profit, not on this phone.',
              ),
            ],
          ),
          const SizedBox(height: 12),
          _section(
            title: 'Distance and weight honesty',
            children: [
              _field(
                _roadFactor,
                label: 'Road distance factor',
                helper: '1.0 quotes the straight line. Leave at 1.0 unless you have measured roads.',
              ),
              _field(
                _assumedWeight,
                label: 'Assumed weight per item with no measured weight (kg)',
                helper: 'Default 0 — do not invent a weight customers will pay for.',
              ),
            ],
          ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: _isSaving ? null : hapticize(_save),
            child: _isSaving
                ? const SizedBox(
                    height: 18,
                    width: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                  )
                : const Text('Save pricing'),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _section({required String title, required List<Widget> children}) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          ...children,
        ],
      ),
    );
  }

  Widget _field(TextEditingController controller, {required String label, required String helper}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextFormField(
        controller: controller,
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))],
        decoration: InputDecoration(labelText: label, helperText: helper, helperMaxLines: 3),
        validator: (value) {
          final text = value?.trim() ?? '';
          if (text.isEmpty) return 'Enter a number';
          if (double.tryParse(text) == null) return 'Enter a valid number';
          return null;
        },
      ),
    );
  }

  static String _num(double value) {
    if (value == value.roundToDouble()) return value.toStringAsFixed(0);
    return value.toString();
  }

  static double _parse(TextEditingController controller) => double.parse(controller.text.trim());
}
