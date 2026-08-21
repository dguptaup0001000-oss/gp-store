import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_cashfree_pg_sdk/api/cferrorresponse/cferrorresponse.dart';
import 'package:flutter_cashfree_pg_sdk/api/cfpayment/cfwebcheckoutpayment.dart';
import 'package:flutter_cashfree_pg_sdk/api/cfpaymentgateway/cfpaymentgatewayservice.dart';
import 'package:flutter_cashfree_pg_sdk/api/cfsession/cfsession.dart';
import 'package:flutter_cashfree_pg_sdk/utils/cfenums.dart';
import 'package:flutter_cashfree_pg_sdk/utils/cfexceptions.dart';

/// What the SDK told us locally. Deliberately NOT a payment result.
///
/// THE NAME IS THE POINT. Every value here is a HINT about what the customer
/// did on the checkout screen, and none of it is evidence that money moved.
/// The app treats all of them the same way: ask the backend. The only thing
/// this changes is the wording shown while that happens.
enum CheckoutOutcome {
  /// The SDK reported completion. Still not proof - see the class doc.
  reportedComplete,

  /// The SDK reported an error, or the customer backed out.
  reportedIncomplete,

  /// The SDK could not be opened at all.
  couldNotOpen,
}

/// Opens Cashfree's hosted checkout and reports what the SDK said.
///
/// THIS CLASS CANNOT CONFIRM A PAYMENT, and that is deliberate rather than a
/// limitation. It holds no credential, makes no call to Cashfree's API, and
/// returns no amount or status the rest of the app is allowed to act on. A
/// compromised or modified build of this app can lie about everything here
/// and still not get an order marked paid, because the only thing that marks
/// an order paid is the backend asking Cashfree directly.
///
/// Web checkout rather than the drop-in or the per-instrument builders: it
/// is the flow Cashfree hosts and keeps current, so UPI apps, cards and
/// netbanking all keep working without this app shipping a screen per
/// instrument - and no card detail ever passes through GP-Store's code.
class CashfreeCheckoutService {
  CashfreeCheckoutService({CFPaymentGatewayService? gateway})
      : _gateway = gateway ?? CFPaymentGatewayService();

  final CFPaymentGatewayService _gateway;

  /// Opens checkout and completes when the SDK reports back.
  ///
  /// The SDK is callback-based; this bridges it to a Future so the caller
  /// can simply await and then go and ask the backend what really happened.
  Future<CheckoutOutcome> open({
    required String orderId,
    required String paymentSessionId,
    required bool production,
  }) async {
    final completer = Completer<CheckoutOutcome>();

    // Guarded because the SDK may invoke a callback more than once on some
    // devices, and completing a Future twice throws. A duplicate callback
    // must not crash the checkout the customer has just paid on.
    void finish(CheckoutOutcome outcome) {
      if (!completer.isCompleted) completer.complete(outcome);
    }

    try {
      _gateway.setCallback(
        (String _) => finish(CheckoutOutcome.reportedComplete),
        (CFErrorResponse _, String __) => finish(CheckoutOutcome.reportedIncomplete),
      );

      final session = CFSessionBuilder()
          .setEnvironment(production ? CFEnvironment.PRODUCTION : CFEnvironment.SANDBOX)
          .setOrderId(orderId)
          .setPaymentSessionId(paymentSessionId)
          .build();

      final checkout = CFWebCheckoutPaymentBuilder().setSession(session).build();
      await _gateway.doPayment(checkout);
    } on CFException catch (e) {
      // The SDK refused to open - a malformed session, an expired one, a
      // device without a browser component. NOT a failed payment: nothing
      // was attempted, so the caller should say so rather than telling the
      // customer their payment failed.
      debugPrint('Cashfree checkout could not open: ${e.message}');
      finish(CheckoutOutcome.couldNotOpen);
    } catch (e) {
      debugPrint('Cashfree checkout error: ${e.runtimeType}');
      finish(CheckoutOutcome.couldNotOpen);
    }

    return completer.future;
  }
}
