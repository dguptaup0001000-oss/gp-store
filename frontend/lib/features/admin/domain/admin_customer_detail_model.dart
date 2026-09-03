/// Everything the shop knows about one customer, as one screen's worth of data.
///
/// Mirrors AdminCustomerDetailResponse on the backend. Every number is
/// tolerant of a null or a different numeric type, because a screen that
/// throws on one missing field shows a shopkeeper nothing at all - and the
/// thing they actually came for is usually the phone number at the top.
class AdminCustomerDetail {
  const AdminCustomerDetail({
    required this.id,
    required this.fullName,
    this.email,
    this.mobileNumber,
    this.role,
    required this.active,
    required this.verified,
    this.profileImageUrl,
    required this.addresses,
    required this.cart,
    required this.wishlist,
    required this.orders,
    required this.engagement,
  });

  final int id;
  final String fullName;
  final String? email;
  final String? mobileNumber;
  final String? role;
  final bool active;
  final bool verified;
  final String? profileImageUrl;

  final List<CustomerAddressLine> addresses;
  final CustomerCartSummary cart;
  final List<CustomerWishlistLine> wishlist;
  final CustomerOrderStats orders;
  final CustomerEngagement engagement;

  factory AdminCustomerDetail.fromJson(Map<String, dynamic> json) {
    final list = json['addresses'] as List? ?? const [];
    final wish = json['wishlist'] as List? ?? const [];
    return AdminCustomerDetail(
      id: json['id'] as int,
      fullName: json['fullName'] as String? ?? '',
      email: json['email'] as String?,
      mobileNumber: json['mobileNumber'] as String?,
      role: json['role'] as String?,
      active: json['active'] as bool? ?? true,
      verified: json['verified'] as bool? ?? false,
      profileImageUrl: json['profileImageUrl'] as String?,
      addresses: list
          .map((e) => CustomerAddressLine.fromJson(e as Map<String, dynamic>))
          .toList(),
      cart: CustomerCartSummary.fromJson(
          json['cart'] as Map<String, dynamic>? ?? const {}),
      wishlist: wish
          .map((e) => CustomerWishlistLine.fromJson(e as Map<String, dynamic>))
          .toList(),
      orders: CustomerOrderStats.fromJson(
          json['orders'] as Map<String, dynamic>? ?? const {}),
      engagement: CustomerEngagement.fromJson(
          json['engagement'] as Map<String, dynamic>? ?? const {}),
    );
  }
}

class CustomerAddressLine {
  const CustomerAddressLine({
    required this.id,
    this.label,
    this.fullName,
    this.mobileNumber,
    required this.address,
    this.landmark,
    this.directions,
    this.pincode,
    required this.isDefault,
    required this.hasLocation,
  });

  final int id;
  final String? label;
  final String? fullName;
  final String? mobileNumber;
  final String address;
  final String? landmark;

  /// The customer's own directions, in their own words, exactly as typed.
  final String? directions;
  final String? pincode;
  final bool isDefault;

  /// Whether a map pin was ever captured. The coordinates themselves are
  /// deliberately not sent to this screen.
  final bool hasLocation;

  factory CustomerAddressLine.fromJson(Map<String, dynamic> json) {
    return CustomerAddressLine(
      id: json['id'] as int? ?? 0,
      label: json['label'] as String?,
      fullName: json['fullName'] as String?,
      mobileNumber: json['mobileNumber'] as String?,
      address: json['address'] as String? ?? '',
      landmark: json['landmark'] as String?,
      directions: json['directions'] as String?,
      pincode: json['pincode'] as String?,
      isDefault: json['isDefault'] as bool? ?? false,
      hasLocation: json['hasLocation'] as bool? ?? false,
    );
  }
}

class CustomerCartSummary {
  const CustomerCartSummary({
    required this.totalItems,
    required this.totalAmount,
    required this.items,
  });

  final int totalItems;
  final double totalAmount;
  final List<CustomerCartLine> items;

  factory CustomerCartSummary.fromJson(Map<String, dynamic> json) {
    final items = json['items'] as List? ?? const [];
    return CustomerCartSummary(
      totalItems: (json['totalItems'] as num?)?.toInt() ?? 0,
      totalAmount: (json['totalAmount'] as num?)?.toDouble() ?? 0,
      items: items
          .map((e) => CustomerCartLine.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}

class CustomerCartLine {
  const CustomerCartLine({
    required this.productName,
    this.pack,
    required this.quantity,
    required this.totalPrice,
    this.imageUrl,
  });

  final String productName;
  final String? pack;
  final int quantity;
  final double totalPrice;
  final String? imageUrl;

  factory CustomerCartLine.fromJson(Map<String, dynamic> json) {
    return CustomerCartLine(
      productName: json['productName'] as String? ?? '',
      pack: json['pack'] as String?,
      quantity: (json['quantity'] as num?)?.toInt() ?? 0,
      totalPrice: (json['totalPrice'] as num?)?.toDouble() ?? 0,
      imageUrl: json['imageUrl'] as String?,
    );
  }
}

class CustomerWishlistLine {
  const CustomerWishlistLine({
    required this.productId,
    required this.productName,
    this.brand,
    this.imageUrl,
  });

  final int productId;
  final String productName;
  final String? brand;
  final String? imageUrl;

  factory CustomerWishlistLine.fromJson(Map<String, dynamic> json) {
    return CustomerWishlistLine(
      productId: (json['productId'] as num?)?.toInt() ?? 0,
      productName: json['productName'] as String? ?? '',
      brand: json['brand'] as String?,
      imageUrl: json['imageUrl'] as String?,
    );
  }
}

class CustomerOrderStats {
  const CustomerOrderStats({
    required this.count,
    required this.lifetimeSpend,
    this.firstOrderDate,
    this.lastOrderDate,
    required this.cancelledCount,
  });

  final int count;
  final double lifetimeSpend;

  /// Deliberately not "joined". The customers table has never recorded a
  /// sign-up date, so showing one would be a guess dressed up as a fact.
  final DateTime? firstOrderDate;
  final DateTime? lastOrderDate;
  final int cancelledCount;

  factory CustomerOrderStats.fromJson(Map<String, dynamic> json) {
    final first = json['firstOrderDate'] as String?;
    final last = json['lastOrderDate'] as String?;
    return CustomerOrderStats(
      count: (json['count'] as num?)?.toInt() ?? 0,
      lifetimeSpend: (json['lifetimeSpend'] as num?)?.toDouble() ?? 0,
      firstOrderDate: first == null ? null : DateTime.tryParse(first),
      lastOrderDate: last == null ? null : DateTime.tryParse(last),
      cancelledCount: (json['cancelledCount'] as num?)?.toInt() ?? 0,
    );
  }
}

class CustomerEngagement {
  const CustomerEngagement({
    required this.totalSeconds,
    required this.sessionCount,
    this.lastSeen,
  });

  final int totalSeconds;
  final int sessionCount;
  final DateTime? lastSeen;

  factory CustomerEngagement.fromJson(Map<String, dynamic> json) {
    final seen = json['lastSeen'] as String?;
    return CustomerEngagement(
      totalSeconds: (json['totalSeconds'] as num?)?.toInt() ?? 0,
      sessionCount: (json['sessionCount'] as num?)?.toInt() ?? 0,
      lastSeen: seen == null ? null : DateTime.tryParse(seen),
    );
  }
}
