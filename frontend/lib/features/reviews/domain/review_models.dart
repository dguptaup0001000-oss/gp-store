import 'package:freezed_annotation/freezed_annotation.dart';

import '../../products/domain/product_models.dart';

part 'review_models.freezed.dart';
part 'review_models.g.dart';

/// Mirrors backend's Review entity - customer is hidden server-side (always
/// "me" viewing my own review, redundant to nest again), product is the
/// existing Product model since the backend nests it in full.
@freezed
class Review with _$Review {
  const factory Review({
    required int id,
    Product? product,
    required int rating,
    String? comment,
    String? reviewDate,
  }) = _Review;

  factory Review.fromJson(Map<String, dynamic> json) => _$ReviewFromJson(json);
}
