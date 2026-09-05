package com.gpstore.store;

import com.gpstore.entity.StoreClosure;
import com.gpstore.entity.StoreOperationsSettings;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.StoreClosureRepository;
import com.gpstore.repository.StoreOperationsSettingsRepository;
import com.gpstore.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    private final StoreOperationsSettingsRepository settingsRepository;
    private final StoreClosureRepository closureRepository;
    private final DeliveryScheduleService scheduleService;
    private final AuditLogService auditLogService;

    public StoreOperationsService(
            StoreOperationsSettingsRepository settingsRepository,
            StoreClosureRepository closureRepository,
            DeliveryScheduleService scheduleService,
            AuditLogService auditLogService) {
        this.settingsRepository = settingsRepository;
        this.closureRepository = closureRepository;
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
