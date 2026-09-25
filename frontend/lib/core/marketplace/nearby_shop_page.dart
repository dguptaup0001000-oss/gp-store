import 'marketplace_models.dart';

/// Wire envelope returned by `/api/marketplace/shops/page`.
class NearbyShopPage {
  const NearbyShopPage({
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
    required this.hasNext,
    required this.shops,
  });

  final int page;
  final int size;
  final int totalElements;
  final int totalPages;
  final bool hasNext;
  final List<Storefront> shops;

  factory NearbyShopPage.fromJson(Map<String, dynamic> json) => NearbyShopPage(
        page: (json['page'] as num?)?.toInt() ?? 0,
        size: (json['size'] as num?)?.toInt() ?? 0,
        totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
        totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
        hasNext: json['hasNext'] == true,
        shops: (json['shops'] as List<dynamic>? ?? const [])
            .whereType<Map>()
            .map((shop) => Storefront.fromJson(Map<String, dynamic>.from(shop)))
            .toList(growable: false),
      );
}
