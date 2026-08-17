/// Mirrors the backend's CloudinarySignatureResponse - short-lived params
/// for one direct-to-Cloudinary upload (see
/// AdminProductsRepository.uploadVariantImage). Plain class rather than
/// freezed like most other models here: never compared for equality, never
/// copied, exists only to carry these five fields from one request to the
/// next - freezed's generated boilerplate would add nothing.
class CloudinarySignature {
  const CloudinarySignature({
    required this.cloudName,
    required this.apiKey,
    required this.timestamp,
    required this.signature,
    required this.folder,
  });

  final String cloudName;
  final String apiKey;
  final int timestamp;
  final String signature;
  final String folder;

  factory CloudinarySignature.fromJson(Map<String, dynamic> json) {
    return CloudinarySignature(
      cloudName: json['cloudName'] as String,
      apiKey: json['apiKey'] as String,
      timestamp: json['timestamp'] as int,
      signature: json['signature'] as String,
      folder: json['folder'] as String,
    );
  }
}
