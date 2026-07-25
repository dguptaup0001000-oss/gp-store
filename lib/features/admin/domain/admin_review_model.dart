class AdminReview {
  const AdminReview({
    required this.id,
    this.customerName,
    this.customerEmail,
    this.productId,
    this.productName,
    required this.rating,
    this.comment,
    this.reviewDate,
  });

  final int id;
  final String? customerName;
  final String? customerEmail;
  final int? productId;
  final String? productName;
  final int rating;
  final String? comment;
  final String? reviewDate;

  factory AdminReview.fromJson(Map<String, dynamic> json) {
    return AdminReview(
      id: json['id'] as int,
      customerName: json['customerName'] as String?,
      customerEmail: json['customerEmail'] as String?,
      productId: json['productId'] as int?,
      productName: json['productName'] as String?,
      rating: json['rating'] as int,
      comment: json['comment'] as String?,
      reviewDate: json['reviewDate'] as String?,
    );
  }
}
