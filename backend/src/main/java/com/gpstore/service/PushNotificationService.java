package com.gpstore.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;

/**
 * Real push notifications via Firebase Cloud Messaging - the actual
 * provider integration that NotificationService's own doc comments called
 * out as still missing ("wiring an actual SMS/email/push provider ... is a
 * separate integration step with real API keys, not something to fake
 * here"). This is that step.
 *
 * Same "off by default, never fatal" pattern as SmsService/MSG91: with
 * firebase.push-enabled=false (the default) or missing credentials, every
 * call here just logs and returns - the app runs fine with zero Firebase
 * setup. Real push REQUIRES a real Firebase project (see
 * application.properties for how to get FIREBASE_CREDENTIALS_BASE64),
 * which can't be fabricated here.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final boolean pushEnabled;
    private final String credentialsBase64;

    // True only once Firebase has actually been initialized successfully -
    // checked before every send so a bad/missing credential degrades to
    // "no push sent", never a startup crash or a 500 on the caller.
    private boolean initialized = false;

    public PushNotificationService(
            @Value("${firebase.push-enabled}") boolean pushEnabled,
            @Value("${firebase.credentials-base64}") String credentialsBase64) {
        this.pushEnabled = pushEnabled;
        this.credentialsBase64 = credentialsBase64;
        initializeIfConfigured();
    }

    private void initializeIfConfigured() {
        if (!pushEnabled) {
            log.info("Push notifications disabled (firebase.push-enabled=false) - FCM sends will be skipped.");
            return;
        }

        if (credentialsBase64 == null || credentialsBase64.isBlank()) {
            log.warn("firebase.push-enabled=true but FIREBASE_CREDENTIALS_BASE64 is not set - FCM sends will be skipped.");
            return;
        }

        try {
            if (FirebaseApp.getApps().isEmpty()) {
                byte[] decoded = Base64.getDecoder().decode(credentialsBase64);
                GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decoded));

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .build();

                FirebaseApp.initializeApp(options);
            }

            initialized = true;
            log.info("Firebase Admin SDK initialized - push notifications are live.");
        } catch (Exception ex) {
            // A malformed base64 value, an expired/revoked service account
            // key, whatever - none of it should take down the whole
            // backend over a notification feature. Logged loudly so it's
            // not silently missed, but never thrown.
            log.error("Failed to initialize Firebase Admin SDK - push notifications disabled: {}", ex.getMessage());
        }
    }

    /**
     * Best-effort single-device push. Silently no-ops (with a log line) if
     * push isn't configured, or if this customer/partner has no fcmToken on
     * record yet (e.g. they've never opened the app on this build, or
     * denied notification permission) - neither is an error condition,
     * both are completely normal/expected states.
     *
     * `data` is the deep-link payload the Flutter app reads on tap (e.g.
     * {"type": "ORDER_STATUS", "orderId": "123"}) - see
     * firebase_messaging_service.dart's onMessageOpenedApp handler.
     */
    public void sendPush(String fcmToken, String title, String body, Map<String, String> data) {
        if (!initialized) {
            log.debug("Push not sent (Firebase not configured): title={}", title);
            return;
        }

        if (fcmToken == null || fcmToken.isBlank()) {
            log.debug("Push not sent (no fcmToken on record): title={}", title);
            return;
        }

        try {
            Message.Builder messageBuilder = Message.builder()
                    .setToken(fcmToken)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build());

            if (data != null) {
                messageBuilder.putAllData(data);
            }

            FirebaseMessaging.getInstance().send(messageBuilder.build());
        } catch (FirebaseMessagingException ex) {
            // UNREGISTERED means the token is stale (app uninstalled, or a
            // new token issued that replaced this one) - logging is enough
            // for now; the next successful app-open re-registers a fresh
            // token anyway (see CustomerController.updateMyFcmToken), so
            // there's no separate cleanup job needed for this.
            log.warn("FCM send failed ({}): {}", ex.getMessagingErrorCode(), ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected error sending push notification", ex);
        }
    }
}
