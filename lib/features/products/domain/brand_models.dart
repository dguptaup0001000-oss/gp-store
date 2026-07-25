import 'package:freezed_annotation/freezed_annotation.dart';

part 'brand_models.freezed.dart';
part 'brand_models.g.dart';

/// Mirrors backend's BrandSummary exactly - deliberately has no logoUrl
/// field. There's no Brand entity in the backend, "brand" is just a plain
/// text field on Product - a fake logo field here would pretend data exists
/// that doesn't. The UI renders an initials-based avatar instead.
@freezed
class BrandSummary with _$BrandSummary {
  const factory BrandSummary({
    required String brand,
    required int productCount,
  }) = _BrandSummary;

  factory BrandSummary.fromJson(Map<String, dynamic> json) => _$BrandSummaryFromJson(json);
}

enum BrandSortOption {
  priceLowHigh('PRICE_LOW_HIGH', 'Price: Low to High'),
  priceHighLow('PRICE_HIGH_LOW', 'Price: High to Low'),
  nameAsc('NAME_ASC', 'Name: A to Z'),
  nameDesc('NAME_DESC', 'Name: Z to A'),
  newest('NEWEST', 'New Arrivals'),
  discount('DISCOUNT', 'Discount'),
  bestSelling('BEST_SELLING', 'Best Selling'),
  highestRated('HIGHEST_RATED', 'Highest Rated');

  const BrandSortOption(this.apiValue, this.label);
  final String apiValue;
  final String label;
}
