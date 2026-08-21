import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../address/domain/address_models.dart';
import '../../address/presentation/address_list_screen.dart';
import '../../address/presentation/address_providers.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../../core/util/idempotency_key.dart';
import '../../cart/presentation/cart_providers.dart';
import '../domain/checkout_models.dart';
import 'checkout_providers.dart';
import 'order_cancellation_countdown_screen.dart';
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

  /// What the customer is currently waiting on, for the button label.
  ///
  /// Named for what is happening rather than for a payment result, because
  /// none of these states IS a payment result - the backend decides that.
  /// "Verifying" in particular is the honest word for the moment after
  /// Cashfree returns and before this app has been told anything true.
  _PayPhase _phase = _PayPhase.idle;

  /// Idempotency key for the checkout currently in progress. Held across
  /// retries on purpose (see _placeOrder) and cleared only once an order has
  /// actually been placed, so a retry of a failed attempt reuses it while a
  /// brand-new checkout gets a fresh one.
  String? _idempotencyKey;
  String? _previewError;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _autoSelectAddress());
  }

  /// Pre-fills the saved address so the user doesn't have to pick one every
  /// time. They can still tap "Change address" to override it.
  Future<void> _autoSelectAddress() async {
    if (_selectedAddress != null) return;

    // Synchronous fast path. The cart screen warms myAddressesProvider before
    // navigating here and that provider now keeps its value briefly, so by
    // the time this runs the list is usually ALREADY resolved. Reading the
    // resolved value directly means the address renders and the preview
    // request starts in the same frame, instead of after an await that had
    // nothing left to wait for.
    //
    // This is the difference between "checkout renders immediately" and
    // "checkout renders after two sequential round trips" - previously the
    // address fetch had to complete before the preview request was even
    // issued.
    final cached = ref.read(myAddressesProvider).valueOrNull;
    if (cached != null && cached.isNotEmpty) {
      setState(() => _selectedAddress = cached.first);
      _fetchPreview();
      return;
    }

    // Slow path: nothing cached (deep link straight to checkout, cache
    // expired, or a cold start). Behaves exactly as before.
    try {
      final addresses = await ref.read(myAddressesProvider.future);
      if (!mounted || addresses.isEmpty) return;
      setState(() => _selectedAddress = addresses.first);
      _fetchPreview();
    } catch (_) {
      // Fall back to manual selection if the list can't be loaded.
    }
  }

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

  /// Shows the 30-second cancellation window (full order summary + product
  /// list, countdown bar) before any order or payment is actually created.
  /// Nothing is sent to the backend until this resolves true - cancelling
  /// here is just closing a screen, no order ever existed to clean up.
  Future<void> _confirmAndPlaceOrder() async {
    final preview = _preview;
    if (preview == null) return;
    HapticFeedback.mediumImpact();
    final cartItems = ref.read(cartControllerProvider).valueOrNull?.items ?? const [];

    final confirmed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => OrderCancellationCountdownScreen(
          preview: preview,
          items: cartItems,
        ),
      ),
    );

    if (!mounted || confirmed != true) return;
    _placeOrder();
  }

  Future<void> _placeOrder() async {
    final address = _selectedAddress;
    final preview = _preview;
    if (address?.id == null || preview == null || !preview.deliverable) return;

    setState(() => _isPlacingOrder = true);

    // Generated once per logical checkout and deliberately NOT regenerated
    // on retry: reusing the key is the entire point. If a previous attempt
    // timed out after the server had already created the order, re-sending
    // the same key returns that original order instead of placing a second
    // one. Generating a fresh key per attempt would defeat this completely
    // and produce exactly the duplicate orders it exists to prevent.
    //
    // Note this is not the same protection as the _isPlacingOrder flag
    // below. That stops a second TAP; this stops a second REQUEST, including
    // ones the UI never initiated (a Dio-level retry on a reset connection,
    // or a request that was actually delivered despite the client timing
    // out). Only the server can settle those, which is why the key exists.
    _idempotencyKey ??= generateIdempotencyKey();

    try {
      final repository = ref.read(checkoutRepositoryProvider);

      final orderResult = await repository.placeOrder(
        addressId: address!.id!,
        paymentMethod: _paymentMethod,
        idempotencyKey: _idempotencyKey!,
        couponCode: _couponController.text.trim(),
      );

      if (!orderResult.success || orderResult.orderId == null) {
        throw Exception(orderResult.message ?? 'Could not place order');
      }

      // The backend now creates the payment inside the order transaction and
      // returns its status (and the UPI link) with the order itself, so the
      // second HTTP request is only needed for older backends that did not.
      //
      // This removes a full round trip - auth, rate limit, routing, TLS -
      // from the moment the customer is staring at a spinner having already
      // committed to buying. Kept as a fallback rather than deleted so the
      // app still works if it is ever pointed at a backend predating this.
      String? upiPaymentLink = orderResult.upiPaymentLink;
      if (orderResult.paymentStatus == null) {
        final paymentResult = await repository.initiatePayment(
          orderId: orderResult.orderId!,
          paymentMethod: _paymentMethod,
        );
        upiPaymentLink = paymentResult.upiPaymentLink;
      }

      // Terminal success: this checkout is finished, so the key retires with
      // it. Anything the customer starts after this is a NEW logical
      // checkout and must carry a new key - reusing this one would make the
      // server replay the order just placed. Cleared here rather than in
      // finally{} precisely because a failure must KEEP the key for retry.
      _idempotencyKey = null;

      // ONLINE runs the gateway before the confirmation screen, so the
      // customer lands on a screen that already knows the real answer
      // rather than one that says "confirmed" and is then contradicted.
      String? verifiedPaymentStatus;
      if (_paymentMethod == 'ONLINE') {
        verifiedPaymentStatus = await _payOnline(orderResult.orderId!);
      }

      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) => OrderConfirmationScreen(
            orderNumber: orderResult.orderNumber ?? '',
            paymentMethod: _paymentMethod,
            upiPaymentLink: upiPaymentLink,
            verifiedPaymentStatus: verifiedPaymentStatus,
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      // The order-placement call above has been seen to succeed - and
      // actually clear the cart server-side - even when this screen goes on
      // to show an error (see the "An unexpected error occurred" issue:
      // placeOrder() can create the order fine and something AFTER that
      // point still throws). Refreshing here unconditionally, not just on
      // the success path, means the cart badge/bar can never be left
      // showing stale "you still have items" state after an attempt that
      // actually went through on the backend.
      ref.invalidate(cartControllerProvider);
      if (mounted) {
        setState(() {
          _isPlacingOrder = false;
          _phase = _PayPhase.idle;
        });
      }
    }
  }

  /// Runs the gateway checkout for an order that already exists.
  ///
  /// THE ORDER IS CREATED FIRST, then paid. That ordering is what makes
  /// every recovery case survivable: whatever happens from here - the app is
  /// killed, the network drops, the callback never arrives - there is a real
  /// order on the server with a real payment row, and its state can be asked
  /// for later. Creating the order only after a successful payment would
  /// mean a paid customer with nothing to show for it.
  ///
  /// Returns the backend's verdict, never the SDK's.
  Future<String> _payOnline(int orderId) async {
    final repository = ref.read(checkoutRepositoryProvider);

    setState(() => _phase = _PayPhase.preparing);
    final checkout = await repository.startCheckoutSession(orderId: orderId);

    setState(() => _phase = _PayPhase.atGateway);
    final outcome = await CashfreeCheckoutService().open(
      orderId: checkout.providerOrderId,
      paymentSessionId: checkout.paymentSessionId,
      production: checkout.production,
    );

    if (outcome == CheckoutOutcome.couldNotOpen) {
      // Nothing was attempted, so this is not a failed payment and must not
      // be described as one. The order stays pending and is retryable.
      throw Exception('Could not open the payment screen. Please try again.');
    }

    // ASKED REGARDLESS OF WHAT THE SDK SAID, including when it reported the
    // customer backed out. A cancelled-looking checkout can still have
    // completed - the customer may have paid in their UPI app and returned
    // before Cashfree updated the screen - and the only way to know is to
    // ask the backend, which asks Cashfree.
    setState(() => _phase = _PayPhase.verifying);
    return repository.verifyPayment(orderId: orderId);
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
                    subtitle: const Text('Pay the shop directly, confirmed by the shop'),
                    contentPadding: EdgeInsets.zero,
                  ),
                  RadioListTile<String>(
                    value: 'ONLINE',
                    groupValue: _paymentMethod,
                    onChanged: (value) => setState(() => _paymentMethod = value!),
                    secondary: const Icon(Icons.credit_card_outlined, color: AppColors.primary),
                    title: const Text('Pay online'),
                    subtitle: const Text('Card, UPI or netbanking, confirmed instantly'),
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
                  onPressed: (_preview != null && _preview!.deliverable && !_isPlacingOrder) ? _confirmAndPlaceOrder : null,
                  // The label follows the PHASE, so a customer who has just
                  // come back from Cashfree sees "Verifying payment" rather
                  // than a spinner that looks identical to the one before
                  // they paid. That difference is the whole reason _PayPhase
                  // exists.
                  child: _isPlacingOrder
                      ? Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const SizedBox(
                                height: 18,
                                width: 18,
                                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)),
                            const SizedBox(width: 10),
                            Text(_phase.label),
                          ],
                        )
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

/// What the customer is waiting on. Not a payment result - see _PayPhase's
/// use in the button label.
enum _PayPhase {
  idle,
  preparing,
  atGateway,
  verifying;

  String get label => switch (this) {
        _PayPhase.idle => 'Placing order...',
        _PayPhase.preparing => 'Preparing payment...',
        _PayPhase.atGateway => 'Opening payment...',
        // The honest word. At this moment the customer may well have paid
        // and this app has not yet been told anything true about it.
        _PayPhase.verifying => 'Verifying payment...',
      };
}
