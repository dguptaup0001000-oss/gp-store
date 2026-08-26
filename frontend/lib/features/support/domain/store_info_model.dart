class StoreInfo {
  const StoreInfo({
    required this.supportPhone,
    required this.supportWhatsapp,
    required this.supportEmail,
    this.supportUrl = '',
    this.onlinePaymentEnabled = false,
    this.upiConfigured = false,
  });

  final String supportPhone;
  final String supportWhatsapp;
  final String supportEmail;
  final String supportUrl;
  final bool onlinePaymentEnabled;
  final bool upiConfigured;

  bool get hasAnyContact =>
      supportPhone.isNotEmpty ||
      supportWhatsapp.isNotEmpty ||
      supportEmail.isNotEmpty ||
      supportUrl.isNotEmpty;

  /// COD is always available. UPI/ONLINE only when the API says they are
  /// actually configured — never advertise a gateway that would 409.
  String coercePaymentMethod(String selected) {
    return coercePaymentMethodFor(selected, this);
  }

  static String coercePaymentMethodFor(String selected, StoreInfo? info) {
    if (selected == 'ONLINE' && info?.onlinePaymentEnabled != true) {
      return 'COD';
    }
    if (selected == 'UPI' && info?.upiConfigured != true) {
      return 'COD';
    }
    return selected;
  }

  factory StoreInfo.fromJson(Map<String, dynamic> json) {
    return StoreInfo(
      supportPhone: _publicOrEmpty(json['supportPhone']),
      supportWhatsapp: _publicOrEmpty(json['supportWhatsapp']),
      supportEmail: _publicOrEmpty(json['supportEmail']),
      supportUrl: _publicOrEmpty(json['supportUrl']),
      onlinePaymentEnabled: json['onlinePaymentEnabled'] == true,
      upiConfigured: json['upiConfigured'] == true,
    );
  }

  /// Client-side belt: the API already strips placeholders. Treat leftover
  /// published fakes as empty so Contact Us never dials XXXXXXXXXX.
  static String _publicOrEmpty(dynamic value) {
    final raw = (value as String? ?? '').trim();
    if (raw.isEmpty) return '';
    final lower = raw.toLowerCase();
    if (lower.contains('example.com') ||
        lower.contains('xxxxxxxx') ||
        lower.contains('placeholder') ||
        lower.contains('change_me') ||
        lower.contains('changeme') ||
        lower.contains('yourstorename') ||
        lower.contains('todo')) {
      return '';
    }
    return raw;
  }
}
