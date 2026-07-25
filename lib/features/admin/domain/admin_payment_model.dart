class AdminPayment {
  const AdminPayment({
    required this.id,
    this.orderId,
    this.orderNumber,
    this.customerName,
    required this.amount,
    this.paymentMethod,
    this.paymentStatus,
    this.transactionId,
    this.paymentDate,
  });

  final int id;
  final int? orderId;
  final String? orderNumber;
  final String? customerName;
  final double amount;
  final String? paymentMethod;
  final String? paymentStatus;
  final String? transactionId;
  final String? paymentDate;

  factory AdminPayment.fromJson(Map<String, dynamic> json) {
    return AdminPayment(
      id: json['id'] as int,
      orderId: json['orderId'] as int?,
      orderNumber: json['orderNumber'] as String?,
      customerName: json['customerName'] as String?,
      amount: (json['amount'] as num).toDouble(),
      paymentMethod: json['paymentMethod'] as String?,
      paymentStatus: json['paymentStatus'] as String?,
      transactionId: json['transactionId'] as String?,
      paymentDate: json['paymentDate'] as String?,
    );
  }
}
