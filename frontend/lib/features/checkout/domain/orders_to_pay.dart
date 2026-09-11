import 'checkout_models.dart';

/// EVERY SHOP THAT IS OWED MONEY, IN THE ORDER THEY WILL BE PAID.
///
/// WHY THIS IS A FUNCTION AND NOT A LINE INSIDE THE CHECKOUT SCREEN. It
/// answers the question a two-shop basket got wrong: a basket spanning two
/// kiranas becomes two orders with two payment rows, each owed to a different
/// merchant, and the screen used to pay `orderId` - the FIRST shop's - and
/// then tell the customer "2 orders placed". They paid one shop and believed
/// they had paid both.
///
/// Pulled out here so the rule can be checked without standing up a payment
/// gateway, which is the only reason it was never checked before.
///
/// THERE IS NO COMBINED PAYMENT AND THERE MUST NEVER BE. This returns a LIST
/// of orders to charge separately, not a total to charge once: the amounts are
/// owed to different merchants, and merging them is exactly what the payment
/// boundary exists to prevent.
List<int> ordersToPay(PlaceOrderResult result) {
  // An older backend that does not send the per-shop breakdown still sends
  // the one order it made. Falling back to it keeps a single-shop checkout
  // working against any backend, which is the case that must never break.
  if (result.shopOrders.isEmpty) {
    final only = result.orderId;
    return only == null ? const [] : <int>[only];
  }

  // THE SERVER'S ORDER IS KEPT. It is the order the shops were split in, and
  // re-sorting here - by amount, by shop, by anything - would mean the
  // sequence of payment screens a customer sees depends on the client build
  // they happen to be running.
  return result.shopOrders.map((shopOrder) => shopOrder.orderId).toList();
}
