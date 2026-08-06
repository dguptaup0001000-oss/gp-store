import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../address/domain/address_models.dart';
import '../../address/presentation/address_list_screen.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../domain/checkout_models.dart';
import 'checkout_providers.dart';
import 'order_confirmation_screen.dart';

class CheckoutScreen extends ConsumerStatefulWidget {
  const CheckoutScreen({super.key});

  @override
  ConsumerState<CheckoutScreen> createState() => _CheckoutScreenState();
}

class _CheckoutScreenState extends ConsumerState<CheckoutScreen> {
  final _couponController = TextEditingController();

  AddressModel? _selectedAddress;
  String _paymentMethod = 'COD';
  CheckoutPreview? _preview;
  bool _isLoadingPreview = false;
  bool _isPlacingOrder = false;
  String? _previewError;

  @override
  void dispose() {
    _couponController.dispose();
    super.dispose();
  }

  Future<void> _selectAddress() async {
    final result = await Navigator.of(context).push<AddressModel>(
      MaterialPageRoute(builder: (_) => const AddressListScreen(selectMode: true)),
    );
    if (result != null) {
      setState(() => _selectedAddress = result);
      _fetchPreview();
    }
  }

  Future<void> _fetchPreview() async {
    final address = _selectedAddress;
    if (address?.id == null) return;

    setState(() {
      _isLoadingPreview = true;
      _previewError = null;
    });

    try {
      final preview = await ref.read(checkoutRepositoryProvider).getPreview(
            addressId: address!.id!,
            couponCode: _couponController.text.trim(),
          );
      if (!mounted) return;
      setState(() => _preview = preview);
    } catch (e) {
      if (!mounted) return;
      setState(() => _previewError = extractErrorMessage(e));
    } finally {
      if (mounted) setState(() => _isLoadingPreview = false);
    }
  }

  Future<void> _placeOrder() async {
    final address = _selectedAddress;
    final preview = _preview;
    if (address?.id == null || preview == null || !preview.deliverable) return;

    setState(() => _isPlacingOrder = true);

    try {
      final repository = ref.read(checkoutRepositoryProvider);

      final orderResult = await repository.placeOrder(
        addressId: address!.id!,
        paymentMethod: _paymentMethod,
        couponCode: _couponController.text.trim(),
      );

      if (!orderResult.success || orderResult.orderId == null) {
        throw Exception(orderResult.message ?? 'Could not place order');
      }

      final paymentResult = await repository.initiatePayment(
        orderId: orderResult.orderId!,
        paymentMethod: _paymentMethod,
      );

      // The cart is cleared server-side once the order is placed - refresh
      // local state so the badge/cart screen reflect that immediately.
      ref.invalidate(cartControllerProvider);

      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) => OrderConfirmationScreen(
            orderNumber: orderResult.orderNumber ?? '',
            paymentMethod: _paymentMethod,
            upiPaymentLink: paymentResult.upiPaymentLink,
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isPlacingOrder = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Checkout')),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _sectionLabel('Delivery Address'),
                  InkWell(
                    onTap: _selectAddress,
                    borderRadius: BorderRadius.circular(12),
                    child: Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: AppColors.cardBackground,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: _selectedAddress == null
                          ? const Row(
                              children: [
                                Icon(Icons.add_location_alt_outlined, color: AppColors.primary),
                                SizedBox(width: 8),
                                Text('Select delivery address'),
                              ],
                            )
                          : Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(_selectedAddress!.fullName, style: const TextStyle(fontWeight: FontWeight.w700)),
                                const SizedBox(height: 4),
                                Text(
                                  '${_selectedAddress!.houseNo}, ${_selectedAddress!.area}, ${_selectedAddress!.city}',
                                  style: Theme.of(context).textTheme.bodyMedium,
                                ),
                                const SizedBox(height: 4),
                                const Text('Change address', style: TextStyle(color: AppColors.primary, fontSize: 12)),
                              ],
                            ),
                    ),
                  ),

                  const SizedBox(height: 20),
                  _sectionLabel('Coupon Code'),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _couponController,
                          textCapitalization: TextCapitalization.characters,
                          decoration: const InputDecoration(hintText: 'Enter coupon code (optional)'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      FilledButton(
                        onPressed: _selectedAddress == null ? null : _fetchPreview,
                        child: const Text('Apply'),
                      ),
                    ],
                  ),

                  const SizedBox(height: 20),
                  _sectionLabel('Payment Method'),
                  RadioListTile<String>(
                    value: 'COD',
                    groupValue: _paymentMethod,
                    onChanged: (value) => setState(() => _paymentMethod = value!),
                    secondary: const Icon(Icons.money_outlined, color: AppColors.success),
                    title: const Text('Cash on Delivery'),
                    contentPadding: EdgeInsets.zero,
                  ),
                  RadioListTile<String>(
                    value: 'UPI',
                    groupValue: _paymentMethod,
                    onChanged: (value) => setState(() => _paymentMethod = value!),
                    secondary: const Icon(Icons.qr_code_scanner_outlined, color: AppColors.primary),
                    title: const Text('UPI (GPay / PhonePe / Paytm)'),
                    contentPadding: EdgeInsets.zero,
                  ),

                  const SizedBox(height: 20),
                  if (_isLoadingPreview)
                    const Center(child: CircularProgressIndicator(strokeWidth: 2))
                  else if (_previewError != null)
                    Center(
                      child: Column(
                        children: [
                          Text(_previewError!),
                          TextButton(onPressed: _fetchPreview, child: const Text('Retry')),
                        ],
                      ),
                    )
                  else if (_preview != null)
                    _PreviewSummary(preview: _preview!),
                ],
              ),
            ),
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: FilledButton(
                  onPressed: (_preview != null && _preview!.deliverable && !_isPlacingOrder) ? _placeOrder : null,
                  child: _isPlacingOrder
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : Text(_preview != null ? 'Place Order - ₹${_preview!.estimatedTotal.toStringAsFixed(0)}' : 'Place Order'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionLabel(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(text, style: Theme.of(context).textTheme.titleMedium),
      );
}

class _PreviewSummary extends StatelessWidget {
  const _PreviewSummary({required this.preview});

  final CheckoutPreview preview;

  @override
  Widget build(BuildContext context) {
    if (!preview.deliverable) {
      return Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: AppColors.error.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(12)),
        child: const Text(
          "Sorry, we don't deliver to this address yet.",
          style: TextStyle(color: AppColors.error, fontWeight: FontWeight.w600),
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (preview.couponError != null) ...[
            Text(preview.couponError!, style: const TextStyle(color: AppColors.error, fontSize: 12)),
            const SizedBox(height: 8),
          ],
          _row('Subtotal', '₹${preview.subtotal.toStringAsFixed(0)}'),
          if (preview.discountAmount > 0) _row('Discount', '-₹${preview.discountAmount.toStringAsFixed(0)}'),
          _row(
            'Delivery Fee',
            preview.freeDeliveryApplied ? 'FREE' : '₹${preview.deliveryFee.toStringAsFixed(0)}',
          ),
          if (preview.estimatedDeliveryMinutes != null)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                'Estimated delivery: ${preview.estimatedDeliveryMinutes} minutes',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12),
              ),
            ),
          const Divider(height: 20),
          _row('Total', '₹${preview.estimatedTotal.toStringAsFixed(0)}', bold: true),
        ],
      ),
    );
  }

  Widget _row(String label, String value, {bool bold = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(fontWeight: bold ? FontWeight.w700 : FontWeight.w400)),
          Text(value, style: TextStyle(fontWeight: bold ? FontWeight.w700 : FontWeight.w400)),
        ],
      ),
    );
  }
}
