package com.gpstore.engagement;

import com.gpstore.entity.Customer;
import com.gpstore.entity.CustomerAppSession;
import com.gpstore.repository.CustomerAppSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Records how long a customer had the app open, without believing them.
 *
 * THE NUMBER ARRIVES FROM A PHONE, which means it is a claim and not a
 * measurement. A device with a wrong clock, an app left running in a pocket,
 * or a modified build can all report anything at all. Everything below exists
 * because of that:
 *
 *   - a session longer than the cap is truncated, not rejected, because the
 *     customer probably did use the app and the honest record is "at least
 *     this long" rather than nothing;
 *   - a session that claims to have ended in the future is refused outright,
 *     since there is no reading of that which is true;
 *   - a customer reporting sessions faster than a human could produce them is
 *     stopped, because a loop is a bug or an attack and either way the rows
 *     are noise.
 *
 * WHAT IT WILL NOT STORE. Duration only. No screen, no search, no product.
 * The request has nowhere to put those, which is the point - a field that
 * does not exist cannot be filled in later by somebody who did not read this
 * comment.
 */
@Service
public class AppSessionService {

    private static final Logger log = LoggerFactory.getLogger(AppSessionService.class);

    private final CustomerAppSessionRepository repository;
    private final int maxSessionSeconds;
    private final int maxSessionsPerHour;
    private final int minSessionSeconds;

    public AppSessionService(
            CustomerAppSessionRepository repository,
            // Four hours. Longer than any real shopping trip and short enough
            // that a phone left unlocked in a pocket overnight cannot claim a
            // customer spent nine hours choosing dal.
            @Value("${engagement.max-session-seconds:14400}") int maxSessionSeconds,
            // A person cannot honestly produce more than this many separate
            // app visits in an hour. Beyond it, something is looping.
            @Value("${engagement.max-sessions-per-hour:60}") int maxSessionsPerHour,
            // Below this it is an accidental tap, not a visit, and storing it
            // would inflate the session COUNT while adding no time.
            @Value("${engagement.min-session-seconds:3}") int minSessionSeconds) {
        this.repository = repository;
        this.maxSessionSeconds = maxSessionSeconds;
        this.maxSessionsPerHour = maxSessionsPerHour;
        this.minSessionSeconds = minSessionSeconds;
    }

    /**
     * @param claimedSeconds what the app says the session lasted.
     * @return the seconds actually recorded, or 0 when nothing was stored.
     *         Never throws for a bad claim: a customer must not see an error
     *         because telemetry about them was malformed.
     */
    @Transactional
    public int record(Customer customer, int claimedSeconds) {
        if (customer == null) {
            return 0;
        }

        if (claimedSeconds < minSessionSeconds) {
            // An accidental tap. Not worth a row.
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();

        long recentlyRecorded = repository.countRecordedSince(customer.getId(), now.minusHours(1));
        if (recentlyRecorded >= maxSessionsPerHour) {
            // Deliberately not an error to the caller. The app is not doing
            // anything a customer chose, and there is nothing they could fix.
            log.info("Ignoring app session for customerId={}: {} already recorded this hour.",
                    customer.getId(), recentlyRecorded);
            return 0;
        }

        int seconds = Math.min(claimedSeconds, maxSessionSeconds);
        if (seconds < claimedSeconds) {
            log.info("Truncated an app session for customerId={} from {}s to the {}s cap.",
                    customer.getId(), claimedSeconds, maxSessionSeconds);
        }

        // THE CLOCK IS THE SERVER'S. Taking the start time from the phone
        // would let a wrong device clock file a session in 1970 or next year,
        // and the "when do people shop" question this table is meant to answer
        // later would be answered with nonsense. The duration is the only
        // thing the client genuinely knows that the server does not.
        CustomerAppSession session = new CustomerAppSession();
        session.setCustomer(customer);
        session.setEndedAt(now);
        session.setStartedAt(now.minus(Duration.ofSeconds(seconds)));
        session.setSeconds(seconds);
        session.setCreatedAt(now);
        repository.save(session);

        return seconds;
    }
}
