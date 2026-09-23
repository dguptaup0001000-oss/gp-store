/// How a shop sells one thing on its shelf.
///
/// ONE SHELF ENTRY, THREE WAYS OF TRADING. A jeweller, a barber and a kirana
/// are not three kinds of product needing three kinds of screen - they are the
/// same listing with a different answer to "how do I get this?". So this is a
/// field on the variant form, not a separate form.
enum SellingMode {
  onlinePurchase('ONLINE_PURCHASE', 'Buy Online',
      'Customers add it to the cart and pay in the app.'),
  visitToBuy('VISIT_TO_BUY', 'Visit to Buy',
      'Customers see it and come to the shop. No online cart or payment.'),
  serviceAtShop('SERVICE_AT_SHOP', 'Service at Shop',
      'A job done at the shop. Customers come to you for it.');

  const SellingMode(this.wire, this.label, this.explanation);

  final String wire;
  final String label;
  final String explanation;

  bool get isOnline => this == SellingMode.onlinePurchase;

  /// Unknown values read as online, which is what every listing was before
  /// modes existed - an older app talking to a newer server sees the world it
  /// already understood rather than an empty screen.
  static SellingMode fromWire(String? wire) {
    for (final mode in SellingMode.values) {
      if (mode.wire == wire) return mode;
    }
    return SellingMode.onlinePurchase;
  }

  /// Merchant-owned catalogue responses must always carry the authoritative
  /// listing mode. Treating a missing/new value as ONLINE_PURCHASE silently
  /// moves Visit-to-Buy and Service listings into the online section after a
  /// reload. Failing the response is safer and makes the wire-contract fault
  /// observable instead of changing what the merchant sells.
  static SellingMode fromRequiredWire(Object? wire) {
    if (wire is String) {
      for (final mode in SellingMode.values) {
        if (mode.wire == wire) return mode;
      }
    }
    throw FormatException('Missing or unsupported commerceMode');
  }
}

/// How the price is stated.
enum PriceMode {
  exact('EXACT_PRICE', 'Exact price'),
  startingFrom('STARTING_FROM', 'Starting from'),
  range('PRICE_RANGE', 'Price range'),
  askAtShop('ASK_AT_SHOP', 'Ask at shop');

  const PriceMode(this.wire, this.label);

  final String wire;
  final String label;

  static PriceMode fromWire(String? wire) {
    for (final mode in PriceMode.values) {
      if (mode.wire == wire) return mode;
    }
    return PriceMode.exact;
  }

  /// Which price modes this selling mode allows.
  ///
  /// AN ONLINE PRICE IS A PROMISE: a cart totals it, a payment charges it and
  /// a receipt prints it, so "from Rs 500" cannot be the number a customer is
  /// billed. The backend refuses the combination outright; the form simply
  /// never offers it, so a merchant meets the rule as an absence rather than
  /// as an error after typing everything in.
  static List<PriceMode> allowedFor(SellingMode selling) {
    return selling.isOnline ? const [PriceMode.exact] : PriceMode.values;
  }
}

/// Whether the thing is actually there, for something bought at the counter.
enum OfflineStock {
  available('AVAILABLE', 'Available'),
  limited('LIMITED_AVAILABILITY', 'Limited'),
  madeToOrder('MADE_TO_ORDER', 'Made to order'),
  outOfStock('OUT_OF_STOCK', 'Out of stock'),
  contactShop('CONTACT_SHOP', 'Call the shop');

  const OfflineStock(this.wire, this.label);

  final String wire;
  final String label;

  static OfflineStock fromWire(String? wire) {
    for (final value in OfflineStock.values) {
      if (value.wire == wire) return value;
    }
    return OfflineStock.available;
  }
}

/// What the merchant typed about how they sell this, ready to send.
///
/// Validated here rather than only at the server so a merchant is told what is
/// wrong beside the field, not after a round trip. The server checks the same
/// rules again and is the one that binds - this is a courtesy, not the guard.
class SellingSetup {
  const SellingSetup({
    required this.selling,
    required this.price,
    this.priceMax,
    this.stock,
    this.serviceMinutes,
  });

  final SellingMode selling;
  final PriceMode price;
  final double? priceMax;
  final OfflineStock? stock;
  final int? serviceMinutes;

  static const int maxServiceMinutes = 8 * 60;

  /// What is wrong with this, in words the merchant can act on. Null is fine.
  String? problem({double? sellingPrice}) {
    if (selling.isOnline && price != PriceMode.exact) {
      return 'An item sold online needs one exact price, because that is what '
          'the customer is charged.';
    }
    if (price == PriceMode.range) {
      if (priceMax == null || priceMax! <= 0) {
        return 'A price range needs a top price.';
      }
      if (sellingPrice != null && priceMax! < sellingPrice) {
        return 'The top of the range cannot be below the starting price.';
      }
    }
    if (selling == SellingMode.serviceAtShop && serviceMinutes != null) {
      if (serviceMinutes! <= 0 || serviceMinutes! > maxServiceMinutes) {
        return 'How long it takes must be between 1 and $maxServiceMinutes minutes.';
      }
    }
    return null;
  }

  /// The fields the variant save sends. Only what this mode actually means:
  /// a product carries no service duration and an online item no shelf stock,
  /// so those go as null rather than as a stale leftover.
  Map<String, dynamic> toJson() {
    return {
      'commerceMode': selling.wire,
      'priceMode': price.wire,
      'priceMax': price == PriceMode.range ? priceMax : null,
      'offlineAvailability': selling.isOnline ? null : (stock ?? OfflineStock.available).wire,
      'serviceDurationMinutes':
          selling == SellingMode.serviceAtShop ? serviceMinutes : null,
    };
  }
}
