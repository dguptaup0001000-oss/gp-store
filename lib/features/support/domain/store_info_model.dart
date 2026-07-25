class StoreInfo {
  const StoreInfo({
    required this.supportPhone,
    required this.supportWhatsapp,
    required this.supportEmail,
  });

  final String supportPhone;
  final String supportWhatsapp;
  final String supportEmail;

  factory StoreInfo.fromJson(Map<String, dynamic> json) {
    return StoreInfo(
      supportPhone: json['supportPhone'] as String? ?? '',
      supportWhatsapp: json['supportWhatsapp'] as String? ?? '',
      supportEmail: json['supportEmail'] as String? ?? '',
    );
  }
}
