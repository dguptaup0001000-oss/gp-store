import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/checkout/data/cashfree_checkout_service.dart';

/// What the app is allowed to conclude from the payment SDK.
///
/// The answer is: nothing. These assertions exist because the single most
/// dangerous shortcut in a payment integration is treating the client
/// callback as proof - and the shape of the code is what prevents it, so
/// the shape is what gets tested.
void main() {
  group('what the client can conclude locally', () {
    test('every SDK outcome is a report, never a payment result', () {
      // The enum deliberately has no "paid" or "succeeded" member. There is
      // no value CashfreeCheckoutService can return that means money moved,
      // so no caller can mistake one for that - by construction rather than
      // by everyone remembering.
      const outcomes = CheckoutOutcome.values;

      expect(outcomes, hasLength(3));
      expect(outcomes.map((o) => o.name),
          containsAll(['reportedComplete', 'reportedIncomplete', 'couldNotOpen']));

      for (final outcome in outcomes) {
        expect(outcome.name.toLowerCase(), isNot(contains('success')),
            reason: 'a local SDK outcome must never be named as a payment result');
        expect(outcome.name.toLowerCase(), isNot(contains('paid')));
      }
    });

    test('"could not open" is distinct from "did not complete"', () {
      // Nothing was attempted, so telling the customer their payment failed
      // would be wrong - and would discourage a retry that should succeed.
      expect(CheckoutOutcome.couldNotOpen, isNot(CheckoutOutcome.reportedIncomplete));
    });
  });
}
