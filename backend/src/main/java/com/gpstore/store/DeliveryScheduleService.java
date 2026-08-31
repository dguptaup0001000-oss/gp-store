package com.gpstore.store;

import com.gpstore.entity.StoreClosure;
import com.gpstore.entity.StoreOperationsSettings;
import com.gpstore.repository.StoreClosureRepository;
import com.gpstore.repository.StoreOperationsSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * THE ONE PLACE THAT ANSWERS "WHAT TIME IS IT AND WHAT DOES THAT MEAN".
 *
 * <p>Nothing outside this class may call {@code LocalDateTime.now()} to decide
 * a delivery date, compare an hour against 21, or ask whether the shop is
 * taking orders. Controllers, the order path, the morning preparation job and
 * the analytics split all come through here. The reason is not tidiness: the
 * same question answered in four files is four chances for one of them to use
 * the server's UTC clock, or to be left behind when the hours change, and the
 * symptom is a customer told a different thing by the banner than by checkout.
 *
 * <p>THE CLOCK IS INJECTED, via the {@code ObjectProvider<Clock>} pattern this
 * codebase already uses (see OtpService, AuthService): production has no Clock
 * bean and gets the system clock, a test can define one and pin 20:59:59.
 * {@link #now()} is the only source of the current instant.
 *
 * <p>THE RULES THEMSELVES ARE NOT HERE - they are in {@link DeliverySchedule},
 * which has no Spring and no database, so the boundary cases are tested by
 * calling a method rather than by standing up a container. This class is the
 * plumbing: where the clock comes from, where the closed days are stored, and
 * caching the latter so a status request does not become a query per day.
 */
@Service
public class DeliveryScheduleService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduleService.class);

    private final StoreScheduleProperties properties;
    private final StoreOperationsSettingsRepository settingsRepository;
    private final StoreClosureRepository closureRepository;
    private final Clock clock;

    public DeliveryScheduleService(
            StoreScheduleProperties properties,
            StoreOperationsSettingsRepository settingsRepository,
            StoreClosureRepository closureRepository,
            ObjectProvider<Clock> clocks) {
        this.properties = properties;
        this.settingsRepository = settingsRepository;
        this.closureRepository = closureRepository;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    /**
     * The current instant, from the injected clock. THE ONLY ONE.
     *
     * <p>systemUTC rather than systemDefaultZone as the fallback, deliberately:
     * an Instant carries no zone, so the two are equivalent in value, and
     * naming UTC removes any suggestion that the JVM's zone matters here. It
     * does not - {@link DeliverySchedule} converts into the shop's zone.
     */
    public Instant now() {
        return clock.instant();
    }

    // ------------------------------------------------------------------
    // The questions the rest of the application asks.
    // ------------------------------------------------------------------

    /** Same-day or night, right now. */
    public StoreMode getCurrentStoreMode() {
        return schedule().mode(now());
    }

    /** The next delivery run, or null if the shop is shut past the lookahead. */
    public DeliveryWindow getNextDeliveryWindow() {
        return schedule().nextWindow(now());
    }

    /**
     * The day an order placed NOW is to be delivered.
     *
     * <p>THE SERVER'S ANSWER, NOT THE CLIENT'S. There is deliberately no
     * overload taking a date from a request: a delivery date in a request body
     * is a value the customer's phone chose, and accepting it lets a phone
     * with a slow clock - or an edited request - book a van for a day of its
     * choosing. See OrderService, which calls this and ignores anything the
     * request said about timing.
     */
    public LocalDate calculateDeliveryDate() {
        return schedule().deliveryDate(now());
    }

    /** What an order placed now would be: SAME_DAY or NEXT_MORNING. */
    public DeliveryType calculateDeliveryType() {
        return schedule().deliveryType(now());
    }

    /** Whether the shop is outside its delivery window right now. */
    public boolean isNightOrder() {
        return getCurrentStoreMode() == StoreMode.NIGHT;
    }

    /** Whether the "same-day ordering closes soon" warning should be showing. */
    public boolean isCountdownActive() {
        return schedule().countdownActive(now());
    }

    /** Time left of same-day ordering, or null when the warning is not showing. */
    public Duration getCountdownRemaining() {
        return schedule().countdownRemaining(now());
    }

    /**
     * Whether a new order may be created right now.
     *
     * <p>THE BACKEND'S ANSWER, AND THE ONE THAT COUNTS. Disabling a button in
     * Flutter stops an honest customer; it does not stop a replayed request,
     * an app left open across the switch being flipped, or a script. The order
     * path calls this and rejects, whatever the client believed.
     */
    public boolean isStoreAcceptingOrders() {
        return schedule().acceptingOrders(now(), acceptance());
    }

    /** Everything at once, for the status endpoint - one settings read, one query. */
    public StoreStatus getStoreStatus() {
        return getStoreStatusAt(now());
    }

    /**
     * The same snapshot, at a caller-supplied instant.
     *
     * <p>WHY A CALLER WOULD PASS ITS OWN INSTANT rather than let this read the
     * clock: checkout stamps the order's date and decides its delivery window
     * from one moment, and an order placed at 20:59:59.999 must not be
     * timestamped inside the window and scheduled outside it. Passing the
     * instant makes that impossible rather than unlikely.
     *
     * <p>This is NOT a way to let a client choose a time. The instant comes
     * from {@link #now()} a few lines earlier in the same server method; there
     * is no path from a request body to this parameter.
     */
    public StoreStatus getStoreStatusAt(Instant at) {
        StoreOperationsSettings settings = settings();
        return schedule().status(at, settings.acceptanceOrDefault(), settings.getClosureMessage());
    }

    /** The window a given order's delivery date belongs to, for display. */
    public DeliveryWindow windowOn(LocalDate date) {
        return schedule().windowOn(date);
    }

    public StoreScheduleProperties getProperties() {
        return properties;
    }

    // ------------------------------------------------------------------
    // Plumbing.
    // ------------------------------------------------------------------

    /**
     * A calculator loaded with the closures that could matter.
     *
     * <p>ONE QUERY, BOUNDED BY THE LOOKAHEAD. The alternative - asking the
     * database "is this day closed?" inside the day-scanning loop - is up to
     * thirty round trips to answer one status request, on the hot path of
     * every product page. Yesterday is included in the range because an order
     * placed at 00:30 in Kanpur is still "yesterday" in UTC, and a range that
     * started at the UTC today would miss it.
     */
    private DeliverySchedule schedule() {
        LocalDate today = now().atZone(properties.getZone()).toLocalDate();
        Set<LocalDate> closed = closedDatesFrom(today.minusDays(1),
                today.plusDays(properties.getMaxClosureLookaheadDays() + 1L));
        return new DeliverySchedule(properties, closed::contains);
    }

    private Set<LocalDate> closedDatesFrom(LocalDate from, LocalDate to) {
        try {
            List<StoreClosure> closures = closureRepository.findBetween(from, to);
            Set<LocalDate> dates = new HashSet<>(closures.size());
            for (StoreClosure closure : closures) {
                dates.add(closure.getClosedOn());
            }
            return dates;
        } catch (Exception ex) {
            // FAILS OPEN, ON PURPOSE, and this is the one place in the feature
            // where that is the right direction. If the closures table cannot
            // be read, treating every day as closed would stop the shop taking
            // orders entirely; treating none as closed means at worst a
            // delivery is promised on a holiday, which a human can fix. An
            // outage that loses every order is the worse failure.
            log.warn("Could not read store closures; treating all days as open", ex);
            return Set.of();
        }
    }

    /**
     * The owner's switch.
     *
     * <p>READ-ONLY. It returns a default object rather than saving one when the
     * row is missing, and that is a deliberate departure from
     * DeliveryPricingService, which saves on read - a read path that writes
     * turns a GET into a transaction that mutates shared state, which in this
     * codebase has already produced one intermittent test failure. The row is
     * created by V33; if it is somehow absent, the shop behaves as AUTO and
     * nothing is written on a read.
     */
    @Transactional(readOnly = true)
    public StoreOperationsSettings settings() {
        return settingsRepository.findById(StoreOperationsSettings.SINGLETON_ID)
                .orElseGet(StoreOperationsSettings::new);
    }

    private StoreOrderAcceptance acceptance() {
        return settings().acceptanceOrDefault();
    }
}
