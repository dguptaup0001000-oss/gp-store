import 'package:freezed_annotation/freezed_annotation.dart';

part 'analytics_models.freezed.dart';
part 'analytics_models.g.dart';

/// Mirrors backend's getSalesSummary() exactly. Revenue deliberately
/// excludes cancelled orders server-side - see AnalyticsService's doc
/// comment - so this number is real achieved revenue, not inflated.
///
/// THE PREVIOUS-PERIOD FIELDS ALL CARRY DEFAULTS, and that is not laziness.
/// The APK and the backend deploy separately: a phone that already has this
/// build installed can be talking to a server that has not been updated yet,
/// and a `required` field missing from that server's JSON is a thrown
/// exception on every dashboard open, not a missing badge. Defaulting to
/// zero degrades to "no comparison shown", which is exactly what a zero
/// baseline means anyway.
@freezed
class SalesSummary with _$SalesSummary {
  const factory SalesSummary({
    required int periodDays,
    required double revenue,
    required int orderCount,
    required int cancelledCount,
    required double averageOrderValue,
    @Default(0.0) double previousRevenue,
    @Default(0) int previousOrderCount,
    @Default(0.0) double revenueChangePercent,
    @Default(0.0) double orderCountChangePercent,

    // NULLABLE, NOT DEFAULTED TO ZERO - unlike the comparison fields above,
    // and for the opposite reason. A missing percentage defaulting to zero
    // degrades to "no badge shown", which is honest. A missing NET REVENUE
    // defaulting to zero would render as "you kept ₹0", which is a lie in
    // the more alarming direction, on the screen a shopkeeper checks to see
    // whether the week went well. Null means "this server does not report
    // it" and the card is simply not drawn.
    double? refunded,
    double? netRevenue,
    // The BASELINE, not the figure - only ever used to decide whether a
    // comparison is meaningful, so zero degrades to "no badge" exactly as
    // previousRevenue does.
    @Default(0.0) double previousNetRevenue,
    @Default(0.0) double netRevenueChangePercent,
  }) = _SalesSummary;

  factory SalesSummary.fromJson(Map<String, dynamic> json) => _$SalesSummaryFromJson(json);
}

/// One day of the dashboard chart.
///
/// The backend emits a point for EVERY day in the window, including days
/// nothing sold, so this list can be plotted positionally - index 0 is the
/// oldest day and the last entry is today. Do not filter it or the shape of
/// the week changes.
///
/// [day] stays a String because it is only ever a label and an x-axis key;
/// parsing it to DateTime here would invite a widget to do timezone maths on
/// a bucket the server already decided.
@freezed
class SalesPoint with _$SalesPoint {
  const factory SalesPoint({
    required String day,
    required double revenue,
    required int orderCount,
  }) = _SalesPoint;

  factory SalesPoint.fromJson(Map<String, dynamic> json) => _$SalesPointFromJson(json);
}

/// A row of the top-products leaderboard.
///
/// [unitsSold] IS UNITS. It used to be the number of orders a product
/// appeared in, mislabelled - twelve packets in one order counted as one.
/// The backend now sums quantity.
///
/// [imageUrl] is null for a product with no photograph, and is already
/// resolved server-side (Worker URL or short-lived signed GET) because the
/// R2 bucket is private. Never build an image URL from it.
@freezed
class TopProduct with _$TopProduct {
  const factory TopProduct({
    required int productId,
    required String productName,
    required int unitsSold,
    @Default(0.0) double revenue,
    String? imageUrl,
  }) = _TopProduct;

  factory TopProduct.fromJson(Map<String, dynamic> json) => _$TopProductFromJson(json);
}
