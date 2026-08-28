import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart';

import '../api/api_client.dart';
import 'image_upload_guard.dart';

/// Catalogue image kinds the backend will sign. Must match ImageKind.java.
enum CatalogImageKind {
  product,
  category;

  String get apiName => name.toUpperCase();
}

/// Provider-independent upload. The UI must not know whether storage is
/// R2, S3, or anything else — only upload / replace / delete.
class ImageUploadService {
  ImageUploadService({required this.apiClient});

  final ApiClient apiClient;

  Future<String> uploadImage({
    required List<int> bytes,
    required CatalogImageKind kind,
    int? ownerId,
  }) async {
    if (bytes.length > ImageUploadGuard.maxBytes) {
      throw ApiException(
        statusCode: 400,
        message: 'That photo is too large. Choose an image under 4 MB.',
      );
    }
    if (!ImageUploadGuard.isAllowedImageBytes(bytes)) {
      throw ApiException(
        statusCode: 400,
        message: 'That file is not a JPEG, PNG, or WebP image.',
      );
    }

    final contentType = ImageUploadGuard.contentTypeForBytes(bytes);
    final signed = await _sign(
      kind: kind,
      contentType: contentType,
      contentLength: bytes.length,
      ownerId: ownerId,
    );

    await _putBytes(signed.uploadUrl, signed.headers, bytes);

    final confirmed = await apiClient.dio.post<Map<String, dynamic>>(
      '/api/uploads/confirm',
      data: {'objectKey': signed.objectKey},
    );
    final objectKey = confirmed.data?['objectKey'];
    if (objectKey is! String || objectKey != signed.objectKey) {
      throw ApiException(
        statusCode: confirmed.statusCode,
        message:
            'Image upload did not return a usable link. The product was not changed.',
      );
    }
    final delivery = confirmed.data?['publicUrl'];
    if (delivery is! String ||
        delivery.isEmpty ||
        !ImageUploadGuard.isAllowedDeliveryUrl(delivery)) {
      throw ApiException(
        statusCode: confirmed.statusCode,
        message:
            'Image upload did not return a usable link. The product was not changed.',
      );
    }
    return delivery;
  }

  Future<String> replaceImage({
    required List<int> bytes,
    required CatalogImageKind kind,
    int? ownerId,
  }) {
    // Upload first. The caller updates the database, then the backend
    // deletes the previous R2 object. Never delete the old image first.
    return uploadImage(bytes: bytes, kind: kind, ownerId: ownerId);
  }

  Future<void> deleteImage(String publicUrl) async {
    await apiClient.dio.post<void>(
      '/api/uploads/delete',
      data: {'publicUrl': publicUrl},
    );
  }

  Future<String?> pickAndUpload({
    required CatalogImageKind kind,
    int? ownerId,
    double maxImageEdge = 1600,
    int imageQuality = 85,
  }) async {
    final picked = await ImagePicker().pickImage(
      source: ImageSource.gallery,
      imageQuality: imageQuality,
      maxWidth: maxImageEdge,
      maxHeight: maxImageEdge,
    );
    if (picked == null) return null;
    return uploadPickedFile(picked, kind: kind, ownerId: ownerId);
  }

  Future<String> uploadPickedFile(
    XFile file, {
    required CatalogImageKind kind,
    int? ownerId,
  }) async {
    final bytes = await file.readAsBytes();
    return uploadImage(bytes: bytes, kind: kind, ownerId: ownerId);
  }

  Future<_SignedUpload> _sign({
    required CatalogImageKind kind,
    required String contentType,
    required int contentLength,
    int? ownerId,
  }) async {
    final response = await apiClient.dio.post<Map<String, dynamic>>(
      '/api/uploads/sign',
      data: {
        'imageType': kind.apiName,
        'contentType': contentType,
        'contentLength': contentLength,
        if (ownerId != null) 'ownerId': ownerId,
      },
    );
    final data = response.data;
    if (data == null) {
      throw ApiException(
        statusCode: response.statusCode,
        message: 'Image upload is not available right now.',
      );
    }
    final uploadUrl = data['uploadUrl'];
    final objectKey = data['objectKey'];
    if (uploadUrl is! String ||
        objectKey is! String ||
        uploadUrl.isEmpty ||
        !uploadUrl.startsWith('https://')) {
      throw ApiException(
        statusCode: response.statusCode,
        message: 'Image upload is not available right now.',
      );
    }
    final rawHeaders = data['headers'];
    final headers = <String, String>{};
    if (rawHeaders is Map) {
      rawHeaders.forEach((key, value) {
        if (key is String && value is String) {
          headers[key] = value;
        }
      });
    }
    return _SignedUpload(
      uploadUrl: uploadUrl,
      objectKey: objectKey,
      headers: headers,
    );
  }

  Future<void> _putBytes(
    String uploadUrl,
    Map<String, String> headers,
    List<int> bytes,
  ) async {
    // Fresh Dio: this request is to object storage, not our API. The shop
    // JWT must never be sent there.
    final storage = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 20),
      sendTimeout: const Duration(seconds: 60),
      receiveTimeout: const Duration(seconds: 30),
      followRedirects: false,
      validateStatus: (status) => status != null && status >= 200 && status < 300,
    ));
    try {
      await storage.put<void>(
        uploadUrl,
        data: bytes,
        options: Options(
          headers: headers,
          contentType: headers['Content-Type'] ?? headers['content-type'],
        ),
      );
    } on DioException catch (e) {
      throw ApiException(
        statusCode: e.response?.statusCode,
        message: 'Image upload failed. Try again.',
      );
    }
  }
}

class _SignedUpload {
  const _SignedUpload({
    required this.uploadUrl,
    required this.objectKey,
    required this.headers,
  });

  final String uploadUrl;
  final String objectKey;
  final Map<String, String> headers;
}
