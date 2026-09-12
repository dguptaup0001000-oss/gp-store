import 'package:dio/dio.dart';
import 'package:gpstore/core/api/api_client.dart';

import 'test_api_client.dart';

/// A scripted two-shop marketplace, for driving the real screens.
///
/// WHAT THIS IS AND IS NOT. It is a canned backend behind the app's real Dio
/// client, so the widgets, providers, models, parsing and navigation under
/// test are the shipped ones. It is NOT the Spring backend, and a journey
/// driven against it proves the APP behaves correctly given the contract -
/// not that the server honours that contract. The server's half is proved by
/// the backend suite (MultiShopCheckoutTest, TheNewSurfacesAreAlsoScopedTest,
/// CrossTenantApiAccessTest); neither suite is a substitute for the other and
/// the report says so.
///
/// STATEFUL ON PURPOSE. Adding to the basket has to change what the basket
/// endpoint answers next, or "the basket separates the two shops" is a test
/// of a fixture rather than of the screen.
class TwoShopMarketplace {
  TwoShopMarketplace() {
    _wire();
  }

  final FakeHttpClientAdapter adapter = FakeHttpClientAdapter();

  static const int shopA = 11;
  static const int shopB = 22;
  static const int categoryId = 5;

  /// Shop A's line, Shop B's line - added by the journey, read back by the
  /// basket endpoints.
  final List<Map<String, dynamic>> _basket = [];

  /// Which shops the customer has named as theirs, for this one category.
  List<int> preferredShopIds = const [];

  /// Every path the app asked for, in order. The journey asserts on this to
  /// show it drove the real endpoints rather than a provider override.
  final List<String> requested = [];

  ApiClient client({int? Function()? activeShopId}) =>
      buildTestApiClient(adapter, activeShopId: activeShopId);

  // ---------------------------------------------------------------- shops

  static Map<String, dynamic> _storefront({
    required int shopId,
    required String name,
    required double distanceKm,
    required bool deliversHere,
    bool acceptingOrders = true,
    bool closedToday = false,
    String? closureReason,
  }) =>
      {
        'shopId': shopId,
        'code': 'SHOP$shopId',
        'displayName': name,
        'latitude': 12.9,
        'longitude': 77.6,
        'logoUrl': null,
        'verificationLevel': 'VERIFIED',
        'verificationBadge': 'GP-STORE verified',
        'maxDeliveryRadiusKm': 8.0,
        'distanceKm': distanceKm,
        'deliversHere': deliversHere,
        'openNow': true,
        'acceptingOrders': acceptingOrders,
        'closedToday': closedToday,
        'closureReason': closureReason,
        'pausedUntil': null,
        'nextDeliveryDate': '2026-09-12',
        'supportPhone': '9000000$shopId',
        'timeZone': 'Asia/Kolkata',
      };

  /// What the app is told when it asks for a shop's own page.
  Map<String, dynamic> _detail(int shopId) {
    final isA = shopId == shopA;
    return {
      'shop': isA
          ? _storefront(
              shopId: shopA, name: 'Shop A', distanceKm: 1.2, deliversHere: true)
          : _storefront(
              shopId: shopB, name: 'Shop B', distanceKm: 4.6, deliversHere: true),
      'trusted': isA,
      'businessName': isA ? 'A Kirana Stores' : 'B Provision Mart',
      'policies': [
        {'kind': 'RETURN', 'body': isA ? 'Returns within 2 days.' : 'No returns on fresh items.'},
        {'kind': 'ABOUT', 'body': isA ? 'Family run since 1998.' : 'Open seven days.'},
      ],
      'rating': {
        'average': isA ? 4.6 : 3.9,
        'count': isA ? 128 : 41,
        'recentAverage': isA ? 4.8 : 3.4,
        'recentCount': isA ? 22 : 9,
        'recentDays': 30,
        'verifiedCount': isA ? 118 : 37,
        'distribution': <String, int>{},
        'topReasons': [
          {'reason': 'ON_TIME', 'count': isA ? 40 : 8, 'praise': true},
        ],
      },
    };
  }

  // ------------------------------------------------------------- products

  /// The same catalogue item, priced by whichever shop is in scope.
  ///
  /// Shop A holds it at ₹100. Shop B has run out, so it has NO PRICE at all -
  /// which is the contract Part 2 §10 put on the wire and the reason
  /// sellingPrice is nullable.
  static Map<String, dynamic> product(int shopId) => {
        'id': 900,
        'name': 'Toor Dal 1kg',
        'brand': 'Local',
        'active': true,
        'category': {'id': categoryId, 'name': 'Dal', 'active': true},
        'images': <String>[],
        'variants': [
          {
            'id': 9001,
            'quantity': 1.0,
            'unit': 'kg',
            'available': true,
            'inStock': shopId == shopA,
            'mrp': 120.0,
            'sellingPrice': shopId == shopA ? 100.0 : null,
            'images': <String>[],
          },
        ],
      };

  /// A second item, so Shop B has something the journey can actually buy.
  static Map<String, dynamic> productAtB() => {
        'id': 901,
        'name': 'Sona Masoori Rice 5kg',
        'brand': 'Local',
        'active': true,
        'category': {'id': categoryId, 'name': 'Dal', 'active': true},
        'images': <String>[],
        'variants': [
          {
            'id': 9011,
            'quantity': 5.0,
            'unit': 'kg',
            'available': true,
            'inStock': true,
            'mrp': 400.0,
            'sellingPrice': 360.0,
            'images': <String>[],
          },
        ],
      };

  // --------------------------------------------------------------- basket

  void addToBasket({required int shopId, required String name, required double price}) {
    _basket.add({
      'cartItemId': 1000 + _basket.length,
      'productId': shopId == shopA ? 900 : 901,
      'productName': name,
      'variantId': shopId == shopA ? 9001 : 9011,
      'quantity': 1,
      'price': price,
      'totalPrice': price,
      'mrp': price + 35,
      'available': true,
      'shopId': shopId,
    });
  }

  Map<String, dynamic> _cart() {
    final shopIds = <int>{for (final line in _basket) line['shopId'] as int};
    return {
      'cartId': 77,
      'items': _basket,
      'totalAmount':
          _basket.fold<double>(0, (sum, line) => sum + (line['totalPrice'] as double)),
      'totalItems': _basket.length,
      'shops': [
        for (final id in shopIds)
          {'shopId': id, 'shopName': id == shopA ? 'Shop A' : 'Shop B'},
      ],
    };
  }

  /// The per-shop basket, with each shop's OWN delivery charge.
  ///
  /// Shop A charges ₹20 to come 1.2 km; Shop B charges ₹45 to come 4.6 km.
  /// Two different shops' decisions, which is exactly why the screen may not
  /// add the lines up itself.
  Map<String, dynamic> _basketByShop() {
    final byShop = <int, List<Map<String, dynamic>>>{};
    for (final line in _basket) {
      byShop.putIfAbsent(line['shopId'] as int, () => []).add(line);
    }
    double delivery(int shopId) => shopId == shopA ? 20.0 : 45.0;

    final sections = byShop.entries.map((entry) {
      final subtotal = entry.value
          .fold<double>(0, (sum, line) => sum + (line['totalPrice'] as double));
      return {
        'shopId': entry.key,
        'shopName': entry.key == shopA ? 'Shop A' : 'Shop B',
        'logoUrl': null,
        'lines': [
          for (final line in entry.value)
            {
              'cartItemId': line['cartItemId'],
              'variantId': line['variantId'],
              'productId': line['productId'],
              'productName': line['productName'],
              'quantity': line['quantity'],
              'unitPrice': line['price'],
              'lineTotal': line['totalPrice'],
            },
        ],
        'subtotal': subtotal,
        'discount': 0.0,
        'deliveryCharge': delivery(entry.key),
        'deliveryKnown': true,
        'shopTotal': subtotal + delivery(entry.key),
        'notes': <String>[],
      };
    }).toList();

    final combined = sections.fold<double>(
        0, (sum, section) => sum + (section['shopTotal'] as double));

    return {
      'shops': sections,
      'informationalCombinedTotal': combined,
      'isSinglePayment': sections.length < 2,
      'deliveryFullyKnown': true,
      'note': sections.length < 2
          ? null
          : 'Each shop is paid separately and delivers separately.',
    };
  }

  // --------------------------------------------------------------- offers

  Map<String, dynamic> _offer(int shopId, {required bool inStock}) => {
        'shopId': shopId,
        'shopName': shopId == shopA ? 'Shop A' : 'Shop B',
        'logoUrl': null,
        'distanceKm': shopId == shopA ? 1.2 : 4.6,
        'deliversHere': true,
        'openNow': true,
        'acceptingOrders': true,
        'verificationLevel': 'VERIFIED',
        'verificationBadge': 'GP-STORE verified',
        'trusted': shopId == shopA,
        'ratingAverage': shopId == shopA ? 4.6 : 3.9,
        'ratingCount': shopId == shopA ? 128 : 41,
        'productId': 900,
        'variantId': 9001,
        'listed': true,
        'inStock': inStock,
        'sellingPrice': inStock ? (shopId == shopA ? 100.0 : 60.0) : null,
        'mrp': 120.0,
        'discount': 0.0,
        'deliveryCharge': shopId == shopA ? 20.0 : 45.0,
        'deliveryChargeKnown': true,
        'finalPayable': inStock ? (shopId == shopA ? 120.0 : 105.0) : null,
        'estimatedDeliveryDate': '2026-09-12',
      };

  // ------------------------------------------------------------------ API

  void _wire() {
    void on(String method, String path, Object Function(RequestOptions) body) {
      adapter.on(method, path, (options) {
        requested.add('${options.method.toUpperCase()} ${options.path}');
        return FakeResponse(body(options));
      });
    }

    on('GET', '/api/marketplace/mode', (_) => {'mode': 'MULTI_SHOP_PRODUCTION', 'multiShop': true});

    // DISCOVERY, WITH A REAL LADDER. No radius is local-first: the shops that
    // can actually deliver here. A radius means the customer pressed "search
    // farther", and the server answers the rung it actually searched plus the
    // sentence describing what it did.
    on('GET', '/api/marketplace/discovery', (options) {
      final radius = options.queryParameters['radiusKm'];
      if (radius == null) {
        return {
          'radiusKm': null,
          'nextRadiusKm': 10.0,
          'maxRadiusKm': 20.0,
          'shops': [
            _storefront(shopId: shopA, name: 'Shop A', distanceKm: 1.2, deliversHere: true),
            _storefront(shopId: shopB, name: 'Shop B', distanceKm: 4.6, deliversHere: true),
          ],
          'askedRadiusKm': null,
          'widened': false,
          'message': null,
        };
      }
      return {
        'radiusKm': 20.0,
        'nextRadiusKm': null,
        'maxRadiusKm': 20.0,
        'shops': [
          _storefront(shopId: shopA, name: 'Shop A', distanceKm: 1.2, deliversHere: true),
          _storefront(shopId: shopB, name: 'Shop B', distanceKm: 4.6, deliversHere: true),
          _storefront(shopId: 33, name: 'Shop C', distanceKm: 14.0, deliversHere: false),
        ],
        'askedRadiusKm': 10.0,
        'widened': true,
        'message': 'No shops within 10 km. Showing results within 20 km.',
      };
    });

    on('GET', '/api/marketplace/shops/$shopA', (_) => _detail(shopA));
    on('GET', '/api/marketplace/shops/$shopB', (_) => _detail(shopB));

    on('GET', '/api/addresses/mine', (_) => [
          {
            'id': 3,
            'fullName': 'A Customer',
            'mobileNumber': '9000000000',
            'houseNo': '12',
            'area': 'MG Road',
            'city': 'Bengaluru',
            'state': 'KA',
            'pincode': '560001',
            'country': 'India',
            'latitude': 12.9,
            'longitude': 77.6,
            'defaultAddress': true,
          }
        ]);

    on('GET', '/api/carts/mine', (_) => _cart());
    on('GET', '/api/carts/mine/by-shop', (_) => _basketByShop());

    on('GET', '/api/discovery/compare', (_) => {
          'variantId': 9001,
          'offers': [_offer(shopB, inStock: true), _offer(shopA, inStock: true)],
          'nearestShopId': shopA,
          'nearestFinalPayable': 120.0,
          'cheapestShopId': shopB,
          'cheapestFinalPayable': 105.0,
          'priceGapMultiplier': 1.25,
          // The server's verdict, not arithmetic this app repeats.
          'fartherSellerQualifies': true,
          'saving': 15.0,
        });

    on('GET', '/api/discovery/preferred', (_) => {
          'variantId': 9001,
          'categoryId': categoryId,
          'preferredShopIds': preferredShopIds,
          'offers': preferredShopIds.contains(shopB)
              ? [_offer(shopB, inStock: true), _offer(shopA, inStock: true)]
              : [_offer(shopA, inStock: true), _offer(shopB, inStock: true)],
          'hasPreference': preferredShopIds.isNotEmpty,
        });

    on('GET', '/api/preferred-shops/$categoryId',
        (_) => {'categoryId': categoryId, 'maxPerCategory': 2, 'shopIds': preferredShopIds});

    adapter.on('PUT', '/api/preferred-shops/$categoryId', (options) {
      requested.add('PUT ${options.path}');
      final sent = (options.data as Map)['shopIds'] as List;
      preferredShopIds = sent.map((e) => e as int).toList();
      return FakeResponse(
          {'categoryId': categoryId, 'maxPerCategory': 2, 'shopIds': preferredShopIds});
    });

    // TWO ORDERS, ONE PER SHOP, FROM ONE CHECKOUT. Never one combined order.
    on('GET', '/api/orders/my-orders', (_) => {
          'content': [
            {
              'orderId': 501,
              'orderNumber': 'GP-A-501',
              'totalAmount': 120.0,
              'orderStatus': 'CONFIRMED',
              'paymentStatus': 'PAID',
              'orderDate': '2026-09-11T10:00:00',
              'shopId': shopA,
              'shopName': 'Shop A',
            },
            {
              'orderId': 502,
              'orderNumber': 'GP-B-502',
              'totalAmount': 405.0,
              'orderStatus': 'PREPARING',
              'paymentStatus': 'PENDING',
              'orderDate': '2026-09-11T10:00:00',
              'shopId': shopB,
              'shopName': 'Shop B',
            },
          ],
          'totalPages': 1,
        });

    on('GET', '/api/orders/groups', (_) => [
          {
            'id': 70,
            'groupNumber': 'GRP-70',
            'totalAmount': 525.0,
            'shopCount': 2,
            'placedAt': '2026-09-11T10:00:00',
            'shopOrders': [
              {
                'orderId': 501,
                'orderNumber': 'GP-A-501',
                'shopId': shopA,
                'shopName': 'Shop A',
                'shopStatus': 'CONFIRMED',
                'paymentStatus': 'PAID',
                'totalAmount': 120.0,
                'deliveryFee': 20.0,
                'cancellable': true,
              },
              {
                'orderId': 502,
                'orderNumber': 'GP-B-502',
                'shopId': shopB,
                'shopName': 'Shop B',
                'shopStatus': 'PREPARING',
                'paymentStatus': 'PENDING',
                'totalAmount': 405.0,
                'deliveryFee': 45.0,
                'cancellable': false,
              },
            ],
          }
        ]);

    on('GET', '/api/orders/groups/70', (options) {
      return {
        'id': 70,
        'groupNumber': 'GRP-70',
        'totalAmount': 525.0,
        'shopCount': 2,
        'placedAt': '2026-09-11T10:00:00',
        'shopOrders': [
          {
            'orderId': 501,
            'orderNumber': 'GP-A-501',
            'shopId': shopA,
            'shopName': 'Shop A',
            'shopStatus': 'CONFIRMED',
            'paymentStatus': 'PAID',
            'totalAmount': 120.0,
            'deliveryFee': 20.0,
            'cancellable': true,
          },
          {
            'orderId': 502,
            'orderNumber': 'GP-B-502',
            'shopId': shopB,
            'shopName': 'Shop B',
            'shopStatus': 'PREPARING',
            'paymentStatus': 'PENDING',
            'totalAmount': 405.0,
            'deliveryFee': 45.0,
            'cancellable': false,
          },
        ],
      };
    });
  }

  /// A scripted empty neighbourhood, for the progressive-radius journey.
  ///
  /// Replaces the discovery handler: the first ask finds nothing, and the
  /// ladder is what turns that into shops the customer can see.
  void nobodyDeliversHereUntilYouLookFarther() {
    adapter.on('GET', '/api/marketplace/discovery', (options) {
      requested.add('GET ${options.path}');
      final radius = options.queryParameters['radiusKm'];
      if (radius == null) {
        return const FakeResponse({
          'radiusKm': null,
          'nextRadiusKm': 10.0,
          'maxRadiusKm': 20.0,
          'shops': <Map<String, dynamic>>[],
          'askedRadiusKm': null,
          'widened': false,
          'message': null,
        });
      }
      return FakeResponse({
        'radiusKm': 20.0,
        'nextRadiusKm': null,
        'maxRadiusKm': 20.0,
        'shops': [
          _storefront(shopId: shopA, name: 'Shop A', distanceKm: 12.0, deliversHere: false),
        ],
        'askedRadiusKm': 10.0,
        'widened': true,
        'message': 'No shops within 10 km. Showing results within 20 km.',
      });
    });
  }
}
