package com.gpstore.notify;

import com.gpstore.exception.BadRequestException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The register of which devices may be pushed to.
 *
 * <h2>The handover problem</h2>
 *
 * <p>A counter phone is not personal property. It gets handed to a new manager,
 * sold on, or borrowed; and in a marketplace the person who signs in next may
 * work for a different merchant entirely. A token is therefore unique
 * PLATFORM-WIDE and a repeat arrival MOVES the row to whoever presented it,
 * rather than adding a second one. Anything else leaves the previous merchant
 * subscribed to a phone they no longer hold - which is the same leak as pushing
 * to every admin, arriving a week later.
 *
 * <p>Signing out does the other half: {@link #forget} disables the row for this
 * device immediately, so the gap between one merchant leaving and the next
 * arriving is not a window in which orders keep landing on the old account.
 */
@Service
public class PushRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(PushRegistrationService.class);

    /** Long enough for any real FCM token, short enough to refuse a paste of something else. */
    private static final int MAX_TOKEN = 4096;

    private final PushRegistrationRepository registrations;

    public PushRegistrationService(PushRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    /**
     * Records that this account, in this app, can be reached on this token.
     *
     * <p>IDEMPOTENT. The app re-registers on every launch and on every token
     * rotation, so this is called far more often than anything changes; calling
     * it twice with the same values must leave one row and change nothing that
     * matters.
     */
    @Transactional
    public PushRegistration remember(Long customerId, String token, PushApp app,
                                     String platform, String deviceId) {
        if (customerId == null) {
            throw new BadRequestException("Who is registering?");
        }
        String cleanToken = require(token);

        PushRegistration row = registrations.findByToken(cleanToken)
                .orElseGet(PushRegistration::new);

        boolean movingAccount = row.getId() != null
                && row.getCustomerId() != null
                && !row.getCustomerId().equals(customerId);
        if (movingAccount) {
            // Worth a line in the log: it is the moment one person's device
            // stops being another person's. No token in the message.
            log.info("A push registration moved to a different account "
                    + "(app={}); the previous account will stop receiving its alerts.",
                    app);
        }

        row.setCustomerId(customerId);
        row.setApp(app.name());
        row.setPlatform(platform == null || platform.isBlank()
                ? "ANDROID" : platform.trim().toUpperCase(java.util.Locale.ROOT));
        row.setToken(cleanToken);
        if (deviceId != null && !deviceId.isBlank()) {
            row.setDeviceId(deviceId.trim());
        }
        // Re-registering re-enables: a token retired as dead that turns up again
        // is alive after all, and the install saying so is better evidence than
        // the failure that retired it.
        row.setEnabled(Boolean.TRUE);
        row.setLastSeenAt(LocalDateTime.now());
        return registrations.save(row);
    }

    /**
     * Signing out on this device.
     *
     * <p>DISABLED, NOT DELETED, and only ever the row for the token presented.
     * The caller proves possession of the token, which is what makes this safe
     * to act on; it never touches the account's other devices, because signing
     * out of the tablet is not signing out of the phone.
     */
    @Transactional
    public boolean forget(Long customerId, String token) {
        if (customerId == null || token == null || token.isBlank()) {
            return false;
        }
        Optional<PushRegistration> found = registrations.findByToken(token.trim());
        if (found.isEmpty()) {
            return false;
        }
        PushRegistration row = found.get();
        // ONLY YOUR OWN. Presenting somebody else's token must not silence their
        // device, so this checks the row belongs to the caller before acting.
        if (!customerId.equals(row.getCustomerId())) {
            return false;
        }
        row.setEnabled(Boolean.FALSE);
        registrations.save(row);
        return true;
    }

    /** Every install this account has registered for one app. */
    @Transactional(readOnly = true)
    public List<PushRegistration> devicesOf(Long customerId, PushApp app) {
        if (customerId == null) {
            return List.of();
        }
        return registrations.findByCustomerIdAndApp(customerId, app.name());
    }

    private static String require(String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("A device registration needs a token.");
        }
        String cleaned = token.trim();
        if (cleaned.length() > MAX_TOKEN) {
            throw new BadRequestException("That does not look like a device token.");
        }
        return cleaned;
    }
}
