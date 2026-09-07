package com.gpstore.store;

import com.gpstore.entity.StoreClosure;
import com.gpstore.store.hours.ShopBusinessHours;
import com.gpstore.store.hours.ShopBusinessHoursRepository;
import com.gpstore.store.hours.ShopHoursOverride;
import com.gpstore.store.hours.ShopHoursOverrideRepository;
import com.gpstore.entity.StoreOperationsSettings;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.StoreClosureRepository;
import com.gpstore.repository.StoreOperationsSettingsRepository;
import com.gpstore.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The owner's controls: the order switch and the days the vans do not run.
 *
 * <p>EVERY CHANGE IS AUDITED. Turning off orders stops the shop earning and
 * closing a day cancels deliveries customers are expecting - both are exactly
 * the sort of action that later needs a "who did this, and when". The existing
 * AuditLogService is reused rather than a second log invented, so these entries
 * appear on the same screen as refunds and status changes.
 *
 * <p>AUTHORIZATION IS NOT HERE. It is in SecurityConfig, on the route, which is
 * the layer a hand-built request cannot skip. This class assumes it is only
 * reached by someone who passed that check, and never re-derives permission
 * from anything in the request.
 */
@Service
public class StoreOperationsService {

    /**
     * How far ahead a closure may be declared.
     *
     * <p>A guard against a typo, not a policy: "closed on 2260-03-02" is
     * almost certainly a slipped keystroke, and a row a decade out sits in
     * the table forever waiting to cancel a day nobody remembers declaring.
     */
    private static final int MAX_CLOSURE_HORIZON_DAYS = 400;

    /**
     * The longest pause that is still a pause.
     *
     * <p>Beyond this a shopkeeper means "closed", and should say so - a
     * "temporary" pause measured in days is one nobody will remember setting,
     * and it reads to a customer as a shop that is open and never delivers.
     */
    private static final int MAX_PAUSE_HOURS = 24;

    private final StoreOperationsSettingsRepository settingsRepository;
    private final StoreClosureRepository closureRepository;
    private final ShopBusinessHoursRepository weeklyHours;
    private final ShopHoursOverrideRepository hourOverrides;
    private final com.gpstore.store.hours.ShopHoursService shopHours;
    private final DeliveryScheduleService scheduleService;
    private final AuditLogService auditLogService;

    public StoreOperationsService(
            StoreOperationsSettingsRepository settingsRepository,
            StoreClosureRepository closureRepository,
            ShopBusinessHoursRepository weeklyHours,
            ShopHoursOverrideRepository hourOverrides,
            com.gpstore.store.hours.ShopHoursService shopHours,
            DeliveryScheduleService scheduleService,
            AuditLogService auditLogService) {
        this.settingsRepository = settingsRepository;
        this.closureRepository = closureRepository;
        this.weeklyHours = weeklyHours;
        this.hourOverrides = hourOverrides;
        this.shopHours = shopHours;
        this.scheduleService = scheduleService;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------------
    // The order switch.
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public StoreOperationsSettings settings() {
        return scheduleService.settings();
    }

    /**
     * Sets AUTO, ON or OFF.
     *
     * <p>The row is created if V33 somehow did not - the alternative is an
     * owner pressing "pause orders" and getting a 404 they cannot act on.
     *
     * @param acceptance the new state; never null, the caller parses it
     * @param message    what customers are shown while orders are off. Kept
     *                   even when switching back to AUTO, so the shop does not
     *                   have to retype it the next time.
     * @param actor      who to record in the audit log
     */
    @Transactional
    public StoreOperationsSettings setOrderAcceptance(
            StoreOrderAcceptance acceptance, String message, String actor) {
        if (acceptance == null) {
            throw new BadRequestException("An order acceptance state is required: AUTO, ON or OFF.");
        }
        // BY SHOP, NOT BY A CONSTANT ID. These settings used to be one row for
        // the whole deployment; they are now one row per shop, found by the
        // shop the credential resolved to. Nothing here reads a shop id from
        // the request.
        Long shopId = com.gpstore.platform.TenantDefaults
                .shopIdForCurrentWork(StoreOperationsSettings.class);
        StoreOperationsSettings settings = settingsRepository
                .findByShopId(shopId)
                .orElseGet(StoreOperationsSettings::new);

        StoreOrderAcceptance previous = settings.acceptanceOrDefault();

        settings.setOrderAcceptance(acceptance);
        if (message != null) {
            String trimmed = message.trim();
            settings.setClosureMessage(trimmed.isEmpty() ? null : truncate(trimmed, 300));
        }
        settings.setUpdatedAt(LocalDateTime.now());
        settings.setUpdatedBy(actor);

        StoreOperationsSettings saved = settingsRepository.save(settings);

        auditLogService.log(
                "STORE_ORDER_ACCEPTANCE_CHANGED",
                "StoreOperationsSettings",
                saved.getId(),
                "acceptance: " + previous + " -> " + acceptance
                        + (saved.getClosureMessage() == null ? "" : ", message: " + saved.getClosureMessage()));

        return saved;
    }

    // ------------------------------------------------------------------
    // Stepping out: a pause that ends by itself.
    // ------------------------------------------------------------------

    /**
     * Stops taking NEW orders until a stated time.
     *
     * <p>THREE CONTROLS, ONE MECHANISM. "Back in 30 minutes", "back at four"
     * and "no more orders today" are the same act with three different times,
     * so they are one method taking the time rather than three flags that
     * could disagree. The rest-of-day case is the shop's own closing time,
     * which is why this cannot be computed by the caller: only the schedule
     * knows when this shop shuts.
     *
     * <p>IT DOES NOT TOUCH ORDERS ALREADY ACCEPTED (§12). Pausing is about the
     * next customer, not about the ones already waiting for their packets: the
     * orders on the board keep their delivery windows, keep their riders, and
     * remain the merchant's to complete. Nothing here reads or writes an
     * order, and AcceptedOrdersSurvivePauseTest is what keeps it that way.
     *
     * @param until  when the shop expects to be taking orders again
     * @param reason shown to customers, so written for them
     */
    @Transactional
    public StoreOperationsSettings pauseUntil(LocalDateTime until, String reason, String actor) {
        if (until == null) {
            throw new BadRequestException("A pause needs a time to end at.");
        }
        LocalDateTime now = localNow();
        if (!until.isAfter(now)) {
            throw new BadRequestException("That time has already passed.");
        }
        if (until.isAfter(now.plusHours(MAX_PAUSE_HOURS))) {
            throw new BadRequestException(
                    "A pause can run for at most " + MAX_PAUSE_HOURS + " hours. To close for "
                            + "longer, stop accepting orders or declare the days closed.");
        }

        StoreOperationsSettings settings = currentSettings();
        settings.setOrderAcceptance(StoreOrderAcceptance.OFF);
        settings.setPausedUntil(until);
        if (reason != null && !reason.isBlank()) {
            settings.setClosureMessage(truncate(reason.trim(), 300));
        }
        settings.setUpdatedAt(LocalDateTime.now());
        settings.setUpdatedBy(actor);
        StoreOperationsSettings saved = settingsRepository.save(settings);

        auditLogService.log("STORE_PAUSED", "StoreOperationsSettings", saved.getId(),
                "paused until " + until);
        return saved;
    }

    /** "Back in N minutes", which is the button a shopkeeper actually presses. */
    @Transactional
    public StoreOperationsSettings pauseForMinutes(int minutes, String reason, String actor) {
        if (minutes <= 0) {
            throw new BadRequestException("A pause has to be at least a minute long.");
        }
        return pauseUntil(localNow().plusMinutes(minutes), reason, actor);
    }

    /**
     * "No more orders today" - paused until this shop's own closing time.
     *
     * <p>Ends at the shop's last run of the day rather than at midnight, so
     * the shop is taking orders again when it next opens rather than at
     * 00:01 while everybody is asleep. A shop with nothing left to run today
     * is paused to the end of the shop-local day instead, which is the same
     * promise with the only time there is.
     */
    @Transactional
    public StoreOperationsSettings pauseForRestOfDay(String reason, String actor) {
        LocalDateTime now = localNow();
        LocalDateTime closing = scheduleService.closingTimeOn(now.toLocalDate());
        LocalDateTime until = closing != null && closing.isAfter(now)
                ? closing
                : now.toLocalDate().atTime(23, 59);
        if (!until.isAfter(now)) {
            until = now.plusMinutes(1);
        }
        return pauseUntil(until, reason, actor);
    }

    /** Back open now, whatever the pause said. */
    @Transactional
    public StoreOperationsSettings resume(String actor) {
        StoreOperationsSettings settings = currentSettings();
        settings.setOrderAcceptance(StoreOrderAcceptance.AUTO);
        settings.setPausedUntil(null);
        settings.setClosureMessage(null);
        settings.setUpdatedAt(LocalDateTime.now());
        settings.setUpdatedBy(actor);
        StoreOperationsSettings saved = settingsRepository.save(settings);

        auditLogService.log("STORE_RESUMED", "StoreOperationsSettings", saved.getId(),
                "taking orders again");
        return saved;
    }

    // ------------------------------------------------------------------
    // The shop's own week.
    // ------------------------------------------------------------------

    /** This shop's weekly sessions, or an empty list while it trades on the deployment's. */
    @Transactional(readOnly = true)
    public List<ShopBusinessHours> weeklyHours() {
        return weeklyHours.wholeWeek();
    }

    /**
     * Replaces the whole week in one go.
     *
     * <p>THE WHOLE WEEK, NEVER A DAY. Sessions are rows and a weekday with no
     * rows is a weekly holiday, so a partial save cannot be told apart from
     * "we have decided not to open on Tuesdays" - and a screen that saved one
     * day at a time would close a shop on Tuesday every time it failed
     * halfway. Taking the week as one value makes that unrepresentable.
     *
     * <p>AN EMPTY WEEK IS NOT "CLOSED FOREVER", it is "we have not said" - the
     * rows go and the shop falls back to the deployment's hours, which is
     * where every shop starts. A shop that means to shut permanently closes
     * itself through its status, which is a different question with a
     * different answer for customers.
     *
     * @param week sessions by weekday; a weekday absent from the map is closed
     */
    @Transactional
    public List<ShopBusinessHours> replaceWeek(Map<DayOfWeek, List<TradingSession>> week, String actor) {
        Map<DayOfWeek, List<TradingSession>> incoming = week == null ? Map.of() : week;
        for (Map.Entry<DayOfWeek, List<TradingSession>> day : incoming.entrySet()) {
            validateDay(day.getKey(), day.getValue());
        }

        // Deleted through the repository rather than by a bulk JPQL delete:
        // deleteAll on rows this shop's filter already narrowed cannot reach
        // another shop's, and a bulk delete would be exactly the unfiltered
        // statement Slice 8 named as the filter's blind spot.
        weeklyHours.deleteAll(weeklyHours.wholeWeek());

        List<ShopBusinessHours> saved = new ArrayList<>();
        for (Map.Entry<DayOfWeek, List<TradingSession>> day : incoming.entrySet()) {
            for (TradingSession session : day.getValue()) {
                ShopBusinessHours row = new ShopBusinessHours();
                row.setDay(day.getKey());
                row.setOpensAt(session.opensAt());
                row.setClosesAt(session.closesAt());
                saved.add(weeklyHours.save(row));
            }
        }

        shopHours.hoursChanged();
        auditLogService.log("SHOP_HOURS_CHANGED", "ShopBusinessHours", null,
                saved.isEmpty()
                        ? "hours cleared; trading on the deployment's configured hours"
                        : saved.size() + " session(s) across " + incoming.size() + " day(s)");
        return saved;
    }

    /** One date's hours instead of that weekday's. */
    @Transactional
    public List<ShopHoursOverride> setHoursOn(LocalDate date, List<TradingSession> sessions,
                                              String reason, String actor) {
        if (date == null) {
            throw new BadRequestException("A date is required.");
        }
        LocalDate today = localNow().toLocalDate();
        if (date.isBefore(today)) {
            throw new BadRequestException(
                    "That date has already passed. Hours can only be set for today onwards.");
        }
        List<TradingSession> incoming = sessions == null ? List.of() : sessions;
        validateDay(date.getDayOfWeek(), incoming);

        hourOverrides.deleteAll(hourOverrides.findByOnDate(date));

        List<ShopHoursOverride> saved = new ArrayList<>();
        for (TradingSession session : incoming) {
            ShopHoursOverride row = new ShopHoursOverride();
            row.setOnDate(date);
            row.setOpensAt(session.opensAt());
            row.setClosesAt(session.closesAt());
            if (reason != null && !reason.isBlank()) {
                row.setReason(truncate(reason.trim(), 300));
            }
            row.setCreatedBy(actor);
            saved.add(hourOverrides.save(row));
        }

        shopHours.hoursChanged();
        auditLogService.log("SHOP_HOURS_OVERRIDE_SET", "ShopHoursOverride", null,
                date + ": " + (saved.isEmpty() ? "back to the usual week" : saved.size() + " session(s)"));
        return saved;
    }

    /** Upcoming special days, for the screen that lists them. */
    @Transactional(readOnly = true)
    public List<ShopHoursOverride> upcomingHourOverrides() {
        return hourOverrides.findUpcoming(localNow().toLocalDate());
    }

    /** One session of one day, as a caller states it. */
    public record TradingSession(LocalTime opensAt, LocalTime closesAt) {}

    /**
     * Sessions have to end after they start, and must not overlap each other.
     *
     * <p>OVERLAP IS REJECTED RATHER THAN MERGED. 09:00-13:00 and 12:00-17:00
     * is somebody mistyping one of the four times, and silently merging them
     * into 09:00-17:00 would hide the typo behind a shop that is open an hour
     * it did not mean to be.
     */
    private static void validateDay(DayOfWeek day, List<TradingSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        List<TradingSession> ordered = new ArrayList<>(sessions);
        ordered.sort(Comparator.comparing(TradingSession::opensAt));
        LocalTime previousClose = null;
        for (TradingSession session : ordered) {
            if (session.opensAt() == null || session.closesAt() == null) {
                throw new BadRequestException("A session needs an opening and a closing time.");
            }
            if (!session.closesAt().isAfter(session.opensAt())) {
                throw new BadRequestException(
                        day + ": " + session.opensAt() + "-" + session.closesAt()
                                + " ends before it starts. A shop that trades past midnight is "
                                + "not something this can express yet.");
            }
            if (previousClose != null && session.opensAt().isBefore(previousClose)) {
                throw new BadRequestException(
                        day + ": the sessions overlap. Two stretches of the same day cannot "
                                + "cover the same hour.");
            }
            previousClose = session.closesAt();
        }
    }

    /** The settings row for the shop in scope, created on first write. */
    private StoreOperationsSettings currentSettings() {
        Long shopId = com.gpstore.platform.TenantDefaults
                .shopIdForCurrentWork(StoreOperationsSettings.class);
        return settingsRepository.findByShopId(shopId).orElseGet(StoreOperationsSettings::new);
    }

    /** Now, in the SHOP's zone - never the server's. */
    private LocalDateTime localNow() {
        return scheduleService.localNow();
    }

    // ------------------------------------------------------------------
    // Full-day closures.
    // ------------------------------------------------------------------

    /**
     * Every closure from today onwards.
     *
     * <p>PAST CLOSURES ARE NOT RETURNED. The screen is for planning; a list
     * that grows by one row per festival forever, with last year's Diwali at
     * the top, is one nobody reads.
     */
    @Transactional(readOnly = true)
    public List<StoreClosure> upcomingClosures() {
        return closureRepository.findUpcoming(today());
    }

    /**
     * Declares a day closed.
     *
     * <p>REJECTS THE PAST. Closing a day that has already happened cannot stop
     * a delivery that already went out; it can only confuse the analytics and
     * the audit trail. A typo'd year is the likely cause, and a rejection says
     * so where a silently-accepted row does not.
     */
    @Transactional
    public StoreClosure addClosure(LocalDate date, String reason, String actor) {
        if (date == null) {
            throw new BadRequestException("A date is required.");
        }
        LocalDate today = today();
        if (date.isBefore(today)) {
            throw new BadRequestException(
                    "That date has already passed. Closures can only be declared for today onwards.");
        }
        if (date.isAfter(today.plusDays(MAX_CLOSURE_HORIZON_DAYS))) {
            throw new BadRequestException(
                    "That date is more than " + MAX_CLOSURE_HORIZON_DAYS
                            + " days away - please check the year.");
        }
        if (closureRepository.findByClosedOn(date).isPresent()) {
            throw new ConflictException(date + " is already marked closed.");
        }

        StoreClosure closure = new StoreClosure();
        closure.setClosedOn(date);
        if (reason != null && !reason.isBlank()) {
            closure.setReason(truncate(reason.trim(), 300));
        }
        closure.setCreatedAt(LocalDateTime.now());
        closure.setCreatedBy(actor);

        StoreClosure saved = closureRepository.save(closure);

        auditLogService.log(
                "STORE_CLOSURE_ADDED",
                "StoreClosure",
                saved.getId(),
                "closed on " + date + (saved.getReason() == null ? "" : ": " + saved.getReason()));

        return saved;
    }

    /** Reopens a day. Audited by date, because the row is gone afterwards. */
    @Transactional
    public void removeClosure(LocalDate date, String actor) {
        StoreClosure closure = closureRepository.findByClosedOn(date)
                .orElseThrow(() -> new ResourceNotFoundException(date + " is not marked closed."));
        Long id = closure.getId();
        closureRepository.delete(closure);

        auditLogService.log("STORE_CLOSURE_REMOVED", "StoreClosure", id, "reopened " + date);
    }

    private LocalDate today() {
        return scheduleService.now()
                .atZone(scheduleService.getProperties().getZone())
                .toLocalDate();
    }

    /** Keeps a pasted essay inside the column rather than failing the save. */
    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
