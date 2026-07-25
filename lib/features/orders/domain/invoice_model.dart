class Invoice {
  const Invoice({
    required this.invoiceId,
    required this.invoiceNumber,
    this.orderNumber,
    this.invoiceDate,
    this.subtotal,
    this.taxAmount,
    this.discountAmount,
    this.deliveryCharge,
    required this.grandTotal,
    this.status,
  });

  final int invoiceId;
  final String invoiceNumber;
  final String? orderNumber;
  final String? invoiceDate;
  final double? subtotal;
  final double? taxAmount;
  final double? discountAmount;
  final double? deliveryCharge;
  final double grandTotal;
  final String? status;

  factory Invoice.fromJson(Map<String, dynamic> json) {
    return Invoice(
      invoiceId: json['invoiceId'] as int,
      invoiceNumber: json['invoiceNumber'] as String,
      orderNumber: json['orderNumber'] as String?,
      invoiceDate: json['invoiceDate'] as String?,
      subtotal: (json['subtotal'] as num?)?.toDouble(),
      taxAmount: (json['taxAmount'] as num?)?.toDouble(),
      discountAmount: (json['discountAmount'] as num?)?.toDouble(),
      deliveryCharge: (json['deliveryCharge'] as num?)?.toDouble(),
      grandTotal: (json['grandTotal'] as num).toDouble(),
      status: json['status'] as String?,
    );
  }
}
