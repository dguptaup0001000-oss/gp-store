package com.gpstore.controller;

import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.StoreStatusResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * "Is the shop taking orders, and when will this arrive?"
 *
 * <p>PUBLIC, and that is the point. A customer browsing at 3am before signing
 * in still needs to be told the shop is open and their order would arrive at
 * 9am. Gating this behind authentication would put a login wall in front of
 * the one message the whole feature exists to deliver.
 *
 * <p>NOTHING HERE IS AN AUTHORIZATION DECISION. It exposes the shop's own
 * opening hours and whether it is currently taking orders - the same facts a
 * sign on the door would carry - and no customer, order or staff data.
 */
@RestController
@RequestMapping("/api/store")
public class StoreStatusController {

    private final DeliveryScheduleService scheduleService;

    public StoreStatusController(DeliveryScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /**
     * The shop's current state.
     *
     * <p>CACHED FOR TEN SECONDS, and no longer. Ten seconds is short enough
     * that the countdown is never wrong by more than a rounding - a
     * minute-long cache would tell someone at 20:59 that they still have two
     * minutes - and long enough to absorb a burst from one app moving between
     * screens.
     *
     * <p>PRIVATE, not public, and that is a real limit rather than a detail:
     * the response embeds the server's clock, so a shared proxy cache would
     * hand a later customer a stale timestamp and reintroduce exactly the
     * clock-skew problem this endpoint exists to solve. The cost of that
     * choice is that the cache is PER CLIENT and does NOT deduplicate across
     * customers: a hundred open apps are a hundred polls, each costing one
     * settings read and one closures query.
     *
     * <p>That is affordable at this shop's scale and deliberately not
     * optimised further. If it ever stops being affordable, the fix is a
     * short server-side cache of the closure set - which changes rarely -
     * NOT a longer HTTP cache, which would blunt the countdown.
     */
    @GetMapping("/status")
    public ResponseEntity<StoreStatusResponse> status() {
        StoreStatusResponse body = StoreStatusResponse.from(
                scheduleService.getStoreStatus(), scheduleService.getProperties());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(10, TimeUnit.SECONDS).cachePrivate())
                .body(body);
    }
}
