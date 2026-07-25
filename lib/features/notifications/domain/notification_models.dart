import 'package:freezed_annotation/freezed_annotation.dart';

part 'notification_models.freezed.dart';
part 'notification_models.g.dart';

/// The nested order is trimmed server-side (see Notification.java's
/// @JsonIgnoreProperties) to just id/orderNumber/status - enough to link
/// back to the order, not the full order contents again.
@freezed
class NotificationOrderRef with _$NotificationOrderRef {
  const factory NotificationOrderRef({
    required int id,
    String? orderNumber,
  }) = _NotificationOrderRef;

  factory NotificationOrderRef.fromJson(Map<String, dynamic> json) => _$NotificationOrderRefFromJson(json);
}

@freezed
class AppNotification with _$AppNotification {
  const factory AppNotification({
    required int id,
    required String title,
    required String message,
    required String sentAt,
    NotificationOrderRef? order,
    @Default(false) bool isRead,
  }) = _AppNotification;

  factory AppNotification.fromJson(Map<String, dynamic> json) => _$AppNotificationFromJson(json);
}
