import 'package:freezed_annotation/freezed_annotation.dart';

part 'analytics_models.freezed.dart';
part 'analytics_models.g.dart';

/// Mirrors backend's getSalesSummary() exactly. Revenue deliberately
/// excludes cancelled orders server-side - see AnalyticsService's doc
/// comment - so this number is real achieved revenue, not inflated.
@freezed
class SalesSummary with _$SalesSummary {
  const factory SalesSummary({
    required int periodDays,
    required double revenue,
    required int orderCount,
    required int cancelledCount,
    required double averageOrderValue,
  }) = _SalesSummary;

  factory SalesSummary.fromJson(Map<String, dynamic> json) => _$SalesSummaryFromJson(json);
}

@freezed
class TopProduct with _$TopProduct {
  const factory TopProduct({
    required int productId,
    required String productName,
    required int unitsSold,
  }) = _TopProduct;

  factory TopProduct.fromJson(Map<String, dynamic> json) => _$TopProductFromJson(json);
}
